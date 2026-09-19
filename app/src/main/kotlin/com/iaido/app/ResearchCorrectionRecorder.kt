package com.iaido.app

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Discrete correction actions a caller can hand to [ResearchCorrectionRecorder.record]. Wire
 * names (used in the serialized payload) are the lowercased enum name, e.g. [CANDIDATE_SELECTED]
 * serializes as "candidate_selected".
 */
enum class CorrectionAction {
    CANDIDATE_SELECTED,
    MANUAL_EDIT,
    UNDO,
    FLOW_CORRECTION,
    ;

    val wireName: String get() = name.lowercase(Locale.ROOT)
}

/**
 * Caller-supplied input for one finished correction action. [sourceText]/[finalText] must be only
 * the affected span itself, never surrounding sentence or document context - there is no field
 * here to carry that. [traceId] should be the current ResearchTrace correlation id when one was
 * captured for the same gesture, or null when none is available - never a fabricated value.
 */
data class CorrectionInput(
    val action: CorrectionAction,
    val sourceText: String,
    val finalText: String,
    val candidates: List<String> = emptyList(),
    val algorithmVersion: Int,
    val traceId: String? = null,
)

/**
 * Bounded, already-normalized correction example ready to serialize. Produced only by
 * [boundedCorrectionRecord] - every instance in existence has already passed the empty/oversized
 * span and candidate-count checks.
 */
data class BoundedCorrectionRecord(
    val correctionId: String,
    val traceId: String?,
    val action: CorrectionAction,
    val sourceText: String,
    val finalText: String,
    val candidates: List<String>,
    val algorithmVersion: Int,
) {
    /** JSON-serialized payload - used directly by tests to assert no context ever leaks in. */
    val serialized: String get() = payloadJson().toString()

    internal fun payloadJson(): JsonObject = buildJsonObject {
        put("event_type", JsonPrimitive("research_correction"))
        put("correction_id", JsonPrimitive(correctionId))
        traceId?.let { put("trace_id", JsonPrimitive(it)) }
        put("action", JsonPrimitive(action.wireName))
        put("source_text", JsonPrimitive(sourceText))
        put("final_text", JsonPrimitive(finalText))
        put("candidates", JsonArray(candidates.map { JsonPrimitive(it) }))
        put("algorithm_version", algorithmVersion)
    }
}

/**
 * Normalizes (Unicode NFC) and bounds one [CorrectionInput] into a [BoundedCorrectionRecord], or
 * returns null when the input must be rejected outright: an empty or oversized source/final
 * span. Candidate alternatives are capped (not rejected) - oversized or blank candidates are
 * dropped from the list and the remainder is truncated to [MAX_CANDIDATES].
 */
internal fun boundedCorrectionRecord(
    input: CorrectionInput,
    correctionId: String = UUID.randomUUID().toString(),
): BoundedCorrectionRecord? {
    val source = normalizeSpan(input.sourceText) ?: return null
    val final = normalizeSpan(input.finalText) ?: return null
    val candidates = input.candidates.mapNotNull(::normalizeSpan).take(MAX_CANDIDATES)
    return BoundedCorrectionRecord(
        correctionId = correctionId,
        traceId = input.traceId,
        action = input.action,
        sourceText = source,
        finalText = final,
        candidates = candidates,
        algorithmVersion = input.algorithmVersion,
    )
}

/**
 * Normalizes [text] to NFC and returns it, or null when it is empty or exceeds
 * [MAX_SPAN_CODE_POINTS] Unicode code points - callers reject the whole span rather than
 * truncating it into a misleading partial example.
 */
private fun normalizeSpan(text: String): String? {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
    if (normalized.isEmpty()) return null
    if (normalized.codePointCount(0, normalized.length) > MAX_SPAN_CODE_POINTS) return null
    return normalized
}

/** Hard cap on one affected source/final span, in Unicode code points. */
internal const val MAX_SPAN_CODE_POINTS = 64

/** Hard cap on the number of candidate alternatives kept per correction. */
internal const val MAX_CANDIDATES = 5

/**
 * Opt-in research correction capture: records one bounded, already-redacted correction example
 * per discrete user action (a suggestion pick, an undo, a flow correction, a manual edit).
 *
 * Unlike ResearchTraceRecorder.consume (called many times per gesture) or
 * DiagnosticsTelemetry.record (batched across many small events), [record] here is called once
 * per finished, discrete correction action - so appending straight to the research queue and
 * scheduling upload inline is the natural-boundary write, not a hot-path regression. [record]
 * must still never be called from a per-touch/per-keystroke loop; callers only invoke it at the
 * point a replacement/undo/flow-correction/manual-edit has actually completed.
 *
 * Every public method is a no-op when research consent is disabled, and never throws into the
 * caller - telemetry failures must never affect keyboard behavior.
 */
class ResearchCorrectionRecorder(
    private val enabled: () -> Boolean,
    private val appendToQueue: (BoundedCorrectionRecord) -> Unit,
    private val scheduleUpload: () -> Unit,
) {
    fun record(event: CorrectionInput) {
        try {
            if (!enabled()) return
            val bounded = boundedCorrectionRecord(event) ?: return
            appendToQueue(bounded)
            scheduleUpload()
        } catch (_: Exception) {
            // Research correction capture is best effort and must never affect keyboard behavior.
        }
    }

    companion object {
        /** Matches ResearchTraceRecorder.CURRENT_ALGORITHM_VERSION numbering scheme. */
        const val CURRENT_ALGORITHM_VERSION = 1
    }
}

/**
 * Process-wide access point for the single [ResearchCorrectionRecorder] instance, mirroring
 * [DiagnosticsTelemetryProvider]'s shape: a lazily-installed recorder wired to the research-plane
 * queue/upload path, with consent kept current via a background Flow collector so the IME's main
 * thread never does a synchronous DataStore read.
 */
object ResearchCorrectionRecorderProvider {
    private const val TELEMETRY_QUEUE_DIRECTORY = "telemetry"

    @Volatile
    private var recorder: ResearchCorrectionRecorder? = null

    @Volatile
    private var consentEnabled: Boolean = false

    private val sessionId = UUID.randomUUID().toString()

    val instance: ResearchCorrectionRecorder?
        get() = recorder

    /** Whether research consent is currently enabled. */
    fun isEnabled(): Boolean = consentEnabled

    fun initialize(context: Context) {
        if (recorder != null) return
        val appContext = context.applicationContext

        recorder = ResearchCorrectionRecorder(
            enabled = ::isEnabled,
            appendToQueue = { record -> telemetryQueue(appContext).append(buildEnvelope(appContext, record)) },
            scheduleUpload = { TelemetryUploadScheduler.schedule(appContext, TelemetryPlane.RESEARCH) },
        )

        collectConsent(appContext.settingsStore.data)
    }

    /**
     * Keeps [consentEnabled] current by collecting [preferences] on a background scope - extracted
     * from [initialize] so the reactive-update behavior is unit-testable with a fake Flow.
     */
    internal fun collectConsent(
        preferences: Flow<Preferences>,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): Job = scope.launch {
        runCatching {
            preferences.collect { prefs -> consentEnabled = researchConsentFromPreferences(prefs).enabled }
        }
    }

    /** Test-only seam: installs an explicit recorder/consent state, bypassing [initialize]. */
    internal fun installForTest(recorder: ResearchCorrectionRecorder?, consentEnabled: Boolean) {
        this.recorder = recorder
        this.consentEnabled = consentEnabled
    }

    /** Test-only seam: restores the singleton to its uninitialized state. */
    internal fun resetForTest() {
        recorder = null
        consentEnabled = false
    }

    private fun telemetryQueue(context: Context) = TelemetryQueue(
        directory = File(context.filesDir, TELEMETRY_QUEUE_DIRECTORY),
        plane = TelemetryPlane.RESEARCH,
        clock = System::currentTimeMillis,
        limits = QueueLimits(),
    )

    private fun buildEnvelope(context: Context, record: BoundedCorrectionRecord): TelemetryEnvelope {
        val installation = runCatching {
            TelemetryInstallationStore(context).getOrCreateInstallation {
                TelemetryInstallation(
                    installationId = UUID.randomUUID().toString(),
                    writeCredential = UUID.randomUUID().toString(),
                    deletionCredential = UUID.randomUUID().toString(),
                )
            }
        }.getOrNull()
        return TelemetryEnvelope(
            schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = record.correctionId,
            batchId = UUID.randomUUID().toString(),
            installationId = installation?.installationId ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            occurredAtMs = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidApi = Build.VERSION.SDK_INT,
            eventType = "research_correction",
            payload = record.payloadJson(),
        )
    }
}
