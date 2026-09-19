package com.iaido.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

enum class WorkerDisposition {
    SUCCESS,
    RETRY,
    FAILURE,
}

internal class TelemetryUploadRunner(
    private val consentEnabled: suspend (TelemetryPlane) -> Boolean,
    private val pendingBatches: suspend (TelemetryPlane) -> List<TelemetryBatch>,
    private val upload: suspend (TelemetryPlane, TelemetryBatch) -> UploadDisposition,
    private val acknowledge: suspend (TelemetryPlane, String) -> Unit,
) {
    suspend fun run(plane: TelemetryPlane): WorkerDisposition {
        return try {
            for (batch in pendingBatches(plane)) {
                if (!consentEnabled(plane)) return WorkerDisposition.SUCCESS
                when (upload(plane, batch)) {
                    UploadDisposition.ACKNOWLEDGED -> acknowledge(plane, batch.batchId)
                    UploadDisposition.RETRY -> return WorkerDisposition.RETRY
                    UploadDisposition.DISCARD -> return WorkerDisposition.FAILURE
                }
            }
            WorkerDisposition.SUCCESS
        } catch (_: Exception) {
            WorkerDisposition.RETRY
        }
    }
}

class TelemetryUploadWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val plane = inputData.getString(INPUT_PLANE)
            ?.let { stored -> TelemetryPlane.entries.firstOrNull { it.name == stored } }
            ?: return Result.failure()
        val endpoint = BuildConfig.IAIDO_TELEMETRY_BASE_URL
        val store = runCatching { TelemetryInstallationStore(applicationContext) }
            .getOrElse { return Result.success() }
        if (!shouldScheduleTelemetry(endpoint, store.writeCredential(plane))) return Result.success()
        val transport = runCatching { TelemetryTransport(URL(endpoint), store) }
            .getOrElse { return Result.failure() }
        val queues = mutableMapOf<TelemetryPlane, TelemetryQueue>()
        fun queue(targetPlane: TelemetryPlane): TelemetryQueue = queues.getOrPut(targetPlane) {
            TelemetryQueue(
                File(applicationContext.filesDir, TELEMETRY_QUEUE_DIRECTORY),
                targetPlane,
                System::currentTimeMillis,
                QueueLimits(),
            )
        }
        val runner = TelemetryUploadRunner(
            consentEnabled = { targetPlane ->
                val preferences = applicationContext.settingsStore.data.first()
                when (targetPlane) {
                    TelemetryPlane.DIAGNOSTICS -> diagnosticsConsentFromPreferences(preferences).enabled
                    TelemetryPlane.RESEARCH -> researchConsentFromPreferences(preferences).enabled
                }
            },
            pendingBatches = { targetPlane -> queue(targetPlane).pendingBatches() },
            upload = { targetPlane, batch ->
                withContext(Dispatchers.IO) { transport.upload(targetPlane, batch) }
            },
            acknowledge = { targetPlane, batchId -> queue(targetPlane).acknowledge(batchId) },
        )
        return when (runner.run(plane)) {
            WorkerDisposition.SUCCESS -> Result.success()
            WorkerDisposition.RETRY -> Result.retry()
            WorkerDisposition.FAILURE -> Result.failure()
        }
    }
}

internal object TelemetryUploadScheduler {
    fun schedule(
        context: Context,
        plane: TelemetryPlane,
        endpoint: String = BuildConfig.IAIDO_TELEMETRY_BASE_URL,
    ): Boolean {
        return try {
            val credential = TelemetryInstallationStore(context.applicationContext).writeCredential(plane)
            if (!shouldScheduleTelemetry(endpoint, credential)) return false
            val request = OneTimeWorkRequestBuilder<TelemetryUploadWorker>()
                .setInputData(workDataOf(INPUT_PLANE to plane.name))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(networkTypeFor(plane, researchWifiOnly(context)))
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                telemetryWorkName(plane),
                ExistingWorkPolicy.KEEP,
                request,
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}

internal fun shouldScheduleTelemetry(endpoint: String, writeCredential: String?): Boolean =
    endpoint.isNotBlank() &&
        !writeCredential.isNullOrBlank() &&
        runCatching { URL(endpoint).protocol.equals("https", ignoreCase = true) }.getOrDefault(false)

internal fun telemetryWorkName(plane: TelemetryPlane): String =
    "iaido-telemetry-${plane.name.lowercase(Locale.ROOT)}"

private const val RESEARCH_NETWORK_POLICY_PREFS = "telemetry_research_network_policy"
private const val RESEARCH_WIFI_ONLY_KEY = "research_wifi_only"

/**
 * Whether research uploads should be constrained to unmetered Wi-Fi. Persisted independently of
 * research consent (Task 6's plane isolation) in its own SharedPreferences file - toggling it
 * never touches diagnostics' constraints, which always stay on NetworkType.CONNECTED. Defaults to
 * true (Wi-Fi only) since research payloads are larger and more sensitive than diagnostics.
 */
internal fun researchWifiOnly(context: Context): Boolean =
    context.applicationContext
        .getSharedPreferences(RESEARCH_NETWORK_POLICY_PREFS, Context.MODE_PRIVATE)
        .getBoolean(RESEARCH_WIFI_ONLY_KEY, true)

internal fun setResearchWifiOnly(context: Context, wifiOnly: Boolean) {
    context.applicationContext
        .getSharedPreferences(RESEARCH_NETWORK_POLICY_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(RESEARCH_WIFI_ONLY_KEY, wifiOnly)
        .apply()
}

/**
 * Upload network constraint for [plane]. Diagnostics always uses NetworkType.CONNECTED (any
 * network); research defaults to NetworkType.UNMETERED (Wi-Fi only) unless the user has opted
 * into uploading over metered connections via [researchWifiOnly].
 */
internal fun networkTypeFor(plane: TelemetryPlane, researchWifiOnly: Boolean): NetworkType =
    if (plane == TelemetryPlane.RESEARCH && researchWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

private const val INPUT_PLANE = "telemetry_plane"
private const val TELEMETRY_QUEUE_DIRECTORY = "telemetry"
