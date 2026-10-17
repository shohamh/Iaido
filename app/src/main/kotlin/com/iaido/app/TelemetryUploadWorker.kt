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
    private val ensureProvisioned: suspend () -> UploadDisposition = { UploadDisposition.ACKNOWLEDGED },
    /** Receives the installation id whose write credential was rejected. */
    private val invalidateCredentials: suspend (String) -> Unit = {},
    /** Removes a batch the collector permanently rejected; removing it from the queue by default. */
    private val discard: suspend (TelemetryPlane, String) -> Unit = acknowledge,
) {
    /**
     * Provisions server-issued credentials if they are missing (never invents them), then uploads
     * batches oldest first. An auth rejection clears the stored credentials and stops without
     * touching the batch, so the next run re-provisions instead of retrying a dead credential.
     * A batch the collector permanently rejects (4xx other than auth, oversize, foreign
     * installation) can never succeed, so it is dropped and the batches behind it still upload;
     * transient failures keep the batch and return RETRY for WorkManager's backoff.
     */
    /**
     * Works in passes. After each pass the queue is read again and any batch not seen yet in this
     * run is uploaded too: a gesture recorded while this worker runs lands in a fresh batch (the
     * ones handed out are sealed) and its own schedule() is dropped by
     * [ExistingWorkPolicy.KEEP], so without this the last gesture of a session would wait on the
     * device for the next gesture. Bounded by [MAX_PASSES]; a queue still growing after that
     * returns RETRY, so WorkManager runs it again after backoff instead of leaving it behind.
     *
     * A 401 retires the rejected credentials and re-provisions once within the same run (the
     * rejected batch is retried under the new installation, which drops it as foreign); a second
     * 401 in one run ends it with FAILURE.
     */
    suspend fun run(plane: TelemetryPlane): WorkerDisposition {
        return try {
            val handled = mutableSetOf<String>()
            var reprovisioned = false
            var unauthorized = false
            var pass = 0
            while (true) {
                if (pass == 0 || unauthorized) {
                    when (ensureProvisioned()) {
                        UploadDisposition.ACKNOWLEDGED -> Unit
                        UploadDisposition.RETRY -> return WorkerDisposition.RETRY
                        UploadDisposition.DISCARD, UploadDisposition.UNAUTHORIZED -> return WorkerDisposition.FAILURE
                    }
                }
                unauthorized = false
                val batches = pendingBatches(plane).filter { it.batchId !in handled }
                if (batches.isEmpty()) return WorkerDisposition.SUCCESS
                if (pass == MAX_PASSES) return WorkerDisposition.RETRY
                pass += 1
                for (batch in batches) {
                    if (!consentEnabled(plane)) return WorkerDisposition.SUCCESS
                    when (upload(plane, batch)) {
                        UploadDisposition.ACKNOWLEDGED -> acknowledge(plane, batch.batchId)
                        UploadDisposition.RETRY -> return WorkerDisposition.RETRY
                        UploadDisposition.DISCARD -> discard(plane, batch.batchId)
                        UploadDisposition.UNAUTHORIZED -> {
                            // A batch holds one installation (TelemetryQueue.append): the rejected one.
                            batch.events.firstOrNull()?.let { invalidateCredentials(it.installationId) }
                            if (reprovisioned) return WorkerDisposition.FAILURE
                            reprovisioned = true
                            unauthorized = true
                            break
                        }
                    }
                    handled += batch.batchId
                }
                // After a 401 the next pass re-provisions and retries the rejected batch.
            }
            @Suppress("UNREACHABLE_CODE")
            WorkerDisposition.SUCCESS
        } catch (_: Exception) {
            WorkerDisposition.RETRY
        }
    }

    companion object {
        /** Passes of "upload everything new" one run makes before handing over to a backoff retry. */
        const val MAX_PASSES = 5
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
        if (!shouldScheduleTelemetry(endpoint)) return Result.success()
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
        suspend fun consentEnabled(targetPlane: TelemetryPlane): Boolean {
            val preferences = applicationContext.settingsStore.data.first()
            return when (targetPlane) {
                TelemetryPlane.DIAGNOSTICS -> diagnosticsConsentFromPreferences(preferences).enabled
                TelemetryPlane.RESEARCH -> researchConsentFromPreferences(preferences).enabled
            }
        }
        val runner = TelemetryUploadRunner(
            consentEnabled = { targetPlane -> consentEnabled(targetPlane) },
            ensureProvisioned = {
                if (store.installation() != null || !consentEnabled(plane)) {
                    UploadDisposition.ACKNOWLEDGED
                } else {
                    withContext(Dispatchers.IO) { transport.provision() }
                }
            },
            invalidateCredentials = { rejected -> withContext(Dispatchers.IO) { store.invalidateWriteCredential(rejected) } },
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
            if (!shouldScheduleTelemetry(endpoint)) return false
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

/**
 * Upload work only needs a configured HTTPS collector. Credentials are deliberately not required:
 * when they are missing (lost Keystore key, restored backup, consent set before provisioning) the
 * worker provisions fresh server-issued ones instead of the plane going silent forever.
 */
internal fun shouldScheduleTelemetry(endpoint: String): Boolean =
    endpoint.isNotBlank() &&
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
