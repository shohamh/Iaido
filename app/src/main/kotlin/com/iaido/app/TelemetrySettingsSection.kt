package com.iaido.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import java.io.File
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Version of the consent disclosure copy shown to users. Bump whenever [telemetryDisclosure]
 * text changes in a way that materially changes what a user agreed to, since a stored
 * [ConsentRecord] carries the version that was in effect when consent was granted.
 */
internal const val TELEMETRY_CONSENT_POLICY_VERSION = 1

private const val DIAGNOSTICS_DISCLOSURE =
    "Shares coarse, enum-only gesture outcomes, crash breadcrumbs, and recognition-latency " +
        "buckets tied to an anonymous installation ID. It does not include words you type, " +
        "suggested text, or raw touch paths."

private const val RESEARCH_DISCLOSURE =
    "In addition to diagnostics, this shares readable typing content and raw touch traces so " +
        "we can improve recognition accuracy. Only turn this on if you are comfortable sharing " +
        "what you type."

/** Plane-specific consent disclosure copy. Diagnostics and research must never share wording. */
fun telemetryDisclosure(plane: TelemetryPlane): String = when (plane) {
    TelemetryPlane.DIAGNOSTICS -> DIAGNOSTICS_DISCLOSURE
    TelemetryPlane.RESEARCH -> RESEARCH_DISCLOSURE
}

/** Stable digest of the disclosure text in effect, recorded alongside consent for auditability. */
internal fun telemetryPolicyDigest(plane: TelemetryPlane): String =
    MessageDigest.getInstance("SHA-256")
        .digest(telemetryDisclosure(plane).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

internal fun TelemetryPlane.settingsLabel(): String = when (this) {
    TelemetryPlane.DIAGNOSTICS -> "Diagnostics"
    TelemetryPlane.RESEARCH -> "Research"
}

internal enum class TelemetryConsentStatus { DISABLED, ENABLED, PROVISIONING_FAILED }

internal data class TelemetryConsentOutcome(val status: TelemetryConsentStatus, val message: String)

/**
 * Consent-transition state machine, independent of Compose/Android so it can be unit tested with
 * fake lambdas. A caller (the Composable in this file, wired to real Android dependencies via
 * [buildTelemetryConsentController]) supplies persistence, queue-deletion, provisioning, and
 * upload-scheduling as injected functions.
 *
 * Every method here operates on exactly the [TelemetryPlane] it is given: diagnostics consent
 * changes must never read or write research state and vice versa, since the two planes are
 * independently revocable per the telemetry plan.
 */
internal class TelemetryConsentController(
    private val now: () -> Long = System::currentTimeMillis,
    private val persistConsent: suspend (TelemetryPlane, ConsentRecord) -> Unit,
    private val deletePendingQueue: suspend (TelemetryPlane) -> Unit,
    private val deleteUploadedData: suspend (TelemetryPlane) -> UploadDisposition,
    private val provisionCredentials: suspend () -> UploadDisposition,
    private val scheduleUpload: suspend (TelemetryPlane) -> Unit,
) {
    suspend fun setEnabled(
        plane: TelemetryPlane,
        enabled: Boolean,
        currentRecord: ConsentRecord,
    ): TelemetryConsentOutcome = if (enabled) enable(plane) else disable(plane, currentRecord)

    /**
     * Persists the consent record (version/timestamp/policy digest) first, then provisions
     * installation credentials if they are not already present, then schedules upload work for
     * this plane only. If provisioning fails, the optimistic consent write is rolled back to
     * disabled so the stored state never claims a plane is enabled when it could not actually be
     * wired up: callers should surface [TelemetryConsentOutcome.message] as actionable status
     * text rather than showing the toggle as on.
     */
    private suspend fun enable(plane: TelemetryPlane): TelemetryConsentOutcome {
        val record = ConsentRecord(
            enabled = true,
            consentVersion = TELEMETRY_CONSENT_POLICY_VERSION,
            acceptedAtMs = now(),
            revokedAtMs = null,
            policyDigest = telemetryPolicyDigest(plane),
        )
        persistConsent(plane, record)

        return if (provisionCredentials() == UploadDisposition.ACKNOWLEDGED) {
            scheduleUpload(plane)
            TelemetryConsentOutcome(
                TelemetryConsentStatus.ENABLED,
                "${plane.settingsLabel()} telemetry enabled.",
            )
        } else {
            persistConsent(plane, ConsentRecord.disabled())
            TelemetryConsentOutcome(
                TelemetryConsentStatus.PROVISIONING_FAILED,
                "Could not set up ${plane.settingsLabel().lowercase()} telemetry. Check your " +
                    "connection and try again.",
            )
        }
    }

    /** Deletes this plane's pending local queue before persisting the disabled consent state. */
    private suspend fun disable(plane: TelemetryPlane, currentRecord: ConsentRecord): TelemetryConsentOutcome {
        deletePendingQueue(plane)
        persistConsent(plane, currentRecord.copy(enabled = false, revokedAtMs = now()))
        return TelemetryConsentOutcome(
            TelemetryConsentStatus.DISABLED,
            "${plane.settingsLabel()} telemetry disabled and pending data deleted.",
        )
    }

    /** Deletes only [plane]'s pending local queue, independent of any consent-state change. */
    suspend fun deletePending(plane: TelemetryPlane) {
        deletePendingQueue(plane)
    }

    /** Invokes only [plane]'s server-side deletion route, independent of local queue state. */
    suspend fun deleteUploaded(plane: TelemetryPlane): UploadDisposition = deleteUploadedData(plane)
}

private const val TELEMETRY_QUEUE_DIRECTORY = "telemetry"

/** Wires [TelemetryConsentController] to real Android dependencies. */
internal fun buildTelemetryConsentController(context: Context): TelemetryConsentController {
    val appContext = context.applicationContext
    val installationStore = TelemetryInstallationStore(appContext)
    val transport = runCatching {
        TelemetryTransport(URL(BuildConfig.IAIDO_TELEMETRY_BASE_URL), installationStore)
    }.getOrNull()

    fun queueFor(plane: TelemetryPlane) = TelemetryQueue(
        directory = File(appContext.filesDir, TELEMETRY_QUEUE_DIRECTORY),
        plane = plane,
        clock = System::currentTimeMillis,
        limits = QueueLimits(),
    )

    return TelemetryConsentController(
        persistConsent = { plane, record -> persistConsentRecord(appContext, plane, record) },
        deletePendingQueue = { plane -> withContext(Dispatchers.IO) { queueFor(plane).deleteAll() } },
        deleteUploadedData = { plane ->
            withContext(Dispatchers.IO) { transport?.delete(plane) ?: UploadDisposition.DISCARD }
        },
        provisionCredentials = {
            withContext(Dispatchers.IO) { transport?.provision() ?: UploadDisposition.DISCARD }
        },
        scheduleUpload = { plane -> TelemetryUploadScheduler.schedule(appContext, plane) },
    )
}

/** Writes [record] into the DataStore keys for exactly [plane], never touching the other plane. */
internal suspend fun persistConsentRecord(context: Context, plane: TelemetryPlane, record: ConsentRecord) {
    context.settingsStore.edit { preferences ->
        when (plane) {
            TelemetryPlane.DIAGNOSTICS -> {
                preferences[diagnosticsConsentKey] = record.enabled
                preferences[diagnosticsConsentVersionKey] = record.consentVersion
                preferences.setOrRemove(diagnosticsAcceptedAtMsKey, record.acceptedAtMs)
                preferences.setOrRemove(diagnosticsRevokedAtMsKey, record.revokedAtMs)
                preferences.setOrRemove(diagnosticsPolicyDigestKey, record.policyDigest)
            }
            TelemetryPlane.RESEARCH -> {
                preferences[researchConsentKey] = record.enabled
                preferences[researchConsentVersionKey] = record.consentVersion
                preferences.setOrRemove(researchAcceptedAtMsKey, record.acceptedAtMs)
                preferences.setOrRemove(researchRevokedAtMsKey, record.revokedAtMs)
                preferences.setOrRemove(researchPolicyDigestKey, record.policyDigest)
            }
        }
    }
}

private fun MutablePreferences.setOrRemove(key: Preferences.Key<Long>, value: Long?) {
    if (value != null) this[key] = value else remove(key)
}

private fun MutablePreferences.setOrRemove(key: Preferences.Key<String>, value: String?) {
    if (value != null) this[key] = value else remove(key)
}

/**
 * The privacy/telemetry settings section: two independently revocable switches (diagnostics and
 * research), each with plane-specific disclosure copy, live status text, and per-plane deletion
 * controls. Enabling research requires an extra confirmation dialog; enabling diagnostics never
 * implies research consent, and vice versa: each switch only ever drives its own plane.
 *
 * This composable is purely additive: it renders its own header/divider and does not read or
 * change any state owned by the rest of [SettingsActivity].
 */
@Composable
internal fun TelemetrySettingsSection(context: Context, scope: CoroutineScope) {
    var diagnosticsRecord by remember { mutableStateOf(ConsentRecord.disabled()) }
    var researchRecord by remember { mutableStateOf(ConsentRecord.disabled()) }
    var diagnosticsStatus by remember { mutableStateOf("") }
    var researchStatus by remember { mutableStateOf("") }
    var diagnosticsBusy by remember { mutableStateOf(false) }
    var researchBusy by remember { mutableStateOf(false) }
    var showResearchConfirmation by remember { mutableStateOf(false) }
    var researchWifiOnlyEnabled by remember { mutableStateOf(true) }
    val controller = remember(context) { buildTelemetryConsentController(context) }

    LaunchedEffect(Unit) {
        val preferences = context.settingsStore.data.first()
        diagnosticsRecord = diagnosticsConsentFromPreferences(preferences)
        researchRecord = researchConsentFromPreferences(preferences)
        researchWifiOnlyEnabled = withContext(Dispatchers.IO) { researchWifiOnly(context) }
    }

    suspend fun refresh() {
        val preferences = context.settingsStore.data.first()
        diagnosticsRecord = diagnosticsConsentFromPreferences(preferences)
        researchRecord = researchConsentFromPreferences(preferences)
    }

    fun applyChange(plane: TelemetryPlane, enabled: Boolean) {
        val currentRecord = if (plane == TelemetryPlane.DIAGNOSTICS) diagnosticsRecord else researchRecord
        scope.launch {
            if (plane == TelemetryPlane.DIAGNOSTICS) diagnosticsBusy = true else researchBusy = true
            val outcome = runCatching { controller.setEnabled(plane, enabled, currentRecord) }
                .getOrElse {
                    TelemetryConsentOutcome(
                        TelemetryConsentStatus.PROVISIONING_FAILED,
                        "Could not update ${plane.settingsLabel().lowercase()} telemetry.",
                    )
                }
            refresh()
            if (plane == TelemetryPlane.DIAGNOSTICS) {
                diagnosticsStatus = outcome.message
                diagnosticsBusy = false
            } else {
                researchStatus = outcome.message
                researchBusy = false
            }
        }
    }

    fun deletePending(plane: TelemetryPlane) {
        scope.launch {
            runCatching { controller.deletePending(plane) }
            val message = "Pending ${plane.settingsLabel().lowercase()} data deleted."
            if (plane == TelemetryPlane.DIAGNOSTICS) diagnosticsStatus = message else researchStatus = message
        }
    }

    fun deleteUploaded(plane: TelemetryPlane) {
        scope.launch {
            val disposition = runCatching { controller.deleteUploaded(plane) }
                .getOrDefault(UploadDisposition.DISCARD)
            val message = if (disposition == UploadDisposition.ACKNOWLEDGED) {
                "Uploaded ${plane.settingsLabel().lowercase()} data deletion requested."
            } else {
                "Could not delete uploaded ${plane.settingsLabel().lowercase()} data. Try again later."
            }
            if (plane == TelemetryPlane.DIAGNOSTICS) diagnosticsStatus = message else researchStatus = message
        }
    }

    fun setWifiOnly(wifiOnly: Boolean) {
        researchWifiOnlyEnabled = wifiOnly
        scope.launch { withContext(Dispatchers.IO) { setResearchWifiOnly(context, wifiOnly) } }
    }

    HorizontalDivider()
    Text("Privacy & diagnostics", style = MaterialTheme.typography.titleMedium)
    Text("Both kinds of telemetry are off by default. You can turn either one off, or delete its data, at any time.")

    TelemetryPlaneToggle(
        title = "Share anonymous diagnostics",
        disclosure = telemetryDisclosure(TelemetryPlane.DIAGNOSTICS),
        enabled = diagnosticsRecord.enabled,
        busy = diagnosticsBusy,
        statusText = diagnosticsStatus,
        onCheckedChange = { enabled -> applyChange(TelemetryPlane.DIAGNOSTICS, enabled) },
        onDeletePending = { deletePending(TelemetryPlane.DIAGNOSTICS) },
        onDeleteUploaded = { deleteUploaded(TelemetryPlane.DIAGNOSTICS) },
    )

    TelemetryPlaneToggle(
        title = "Contribute typing research data",
        disclosure = telemetryDisclosure(TelemetryPlane.RESEARCH),
        enabled = researchRecord.enabled,
        busy = researchBusy,
        statusText = researchStatus,
        onCheckedChange = { enabled ->
            if (enabled) showResearchConfirmation = true else applyChange(TelemetryPlane.RESEARCH, false)
        },
        onDeletePending = { deletePending(TelemetryPlane.RESEARCH) },
        onDeleteUploaded = { deleteUploaded(TelemetryPlane.RESEARCH) },
    )

    // Independent of research consent itself (Task 6's plane-isolation pattern): this only ever
    // changes the research upload network constraint, never diagnostics', and never the consent
    // record - it can be changed whether or not research is currently enabled.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Upload research data on Wi-Fi only", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = researchWifiOnlyEnabled, onCheckedChange = ::setWifiOnly)
    }

    if (showResearchConfirmation) {
        AlertDialog(
            onDismissRequest = { showResearchConfirmation = false },
            title = { Text("Contribute typing research data?") },
            text = { Text(telemetryDisclosure(TelemetryPlane.RESEARCH)) },
            confirmButton = {
                TextButton(onClick = {
                    showResearchConfirmation = false
                    applyChange(TelemetryPlane.RESEARCH, true)
                }) { Text("Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showResearchConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TelemetryPlaneToggle(
    title: String,
    disclosure: String,
    enabled: Boolean,
    busy: Boolean,
    statusText: String,
    onCheckedChange: (Boolean) -> Unit,
    onDeletePending: () -> Unit,
    onDeleteUploaded: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title)
                Text(disclosure, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, onCheckedChange = onCheckedChange, enabled = !busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDeletePending) { Text("Delete pending data") }
            TextButton(onClick = onDeleteUploaded) { Text("Delete uploaded data") }
        }
        if (statusText.isNotBlank()) {
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}
