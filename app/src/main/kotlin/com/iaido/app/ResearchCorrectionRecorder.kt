package com.iaido.app

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

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
 * Normalizes (Unicode NFC) and bounds one [CorrectionInput] into a [BoundedResearchCorrection], or
 * returns null when the input must be rejected outright: an empty or oversized source/final
 * span. Candidate alternatives are capped (not rejected) - oversized or blank candidates are
 * dropped from the list and the remainder is truncated to [BoundedResearchCorrection.MAX_CANDIDATES].
 */
internal fun boundedCorrectionRecord(
    input: CorrectionInput,
    correctionId: String = UUID.randomUUID().toString(),
): BoundedResearchCorrection? {
    val source = boundedResearchSpan(input.sourceText) ?: return null
    val final = boundedResearchSpan(input.finalText) ?: return null
    val candidates = input.candidates
        .mapNotNull(::boundedResearchSpan)
        .take(BoundedResearchCorrection.MAX_CANDIDATES)
    return BoundedResearchCorrection(
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
    private val appendToQueue: (BoundedResearchCorrection) -> Unit,
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

    @Volatile
    private var eventSink: ((ResearchEvent) -> Unit)? = null

    @Volatile
    private var uploadScheduler: (() -> Unit)? = null

    private val sessionId = UUID.randomUUID().toString()

    val instance: ResearchCorrectionRecorder?
        get() = recorder

    /** Whether research consent is currently enabled. */
    fun isEnabled(): Boolean = consentEnabled

    fun initialize(context: Context) {
        if (recorder != null) return
        val appContext = context.applicationContext

        eventSink = { event -> telemetryQueue(appContext).append(buildEnvelope(appContext, event)) }
        uploadScheduler = { TelemetryUploadScheduler.schedule(appContext, TelemetryPlane.RESEARCH) }
        recorder = ResearchCorrectionRecorder(
            enabled = ::isEnabled,
            appendToQueue = { record -> eventSink?.invoke(ResearchEvent.Correction(record)) },
            scheduleUpload = { uploadScheduler?.invoke() },
        )

        collectConsent(appContext.settingsStore.data)
    }

    /**
     * Records one finished gesture trace on the research plane, bounded by the same caps the
     * server enforces. No-ops (never throws) when research consent is disabled or when no sink is
     * installed, so a caller can hand it every captured trace without a consent check of its own.
     */
    fun recordTrace(trace: ResearchTrace) {
        try {
            if (!consentEnabled) return
            val bounded = boundedTrace(trace) ?: return
            val sink = eventSink ?: return
            sink(ResearchEvent.GestureTrace(bounded))
            uploadScheduler?.invoke()
        } catch (_: Exception) {
            // Research trace capture is best effort and must never affect keyboard behavior.
        }
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

    /** Test-only seam: installs an explicit event sink, bypassing [initialize]'s queue wiring. */
    internal fun installSinkForTest(sink: ((ResearchEvent) -> Unit)?) {
        eventSink = sink
    }

    /** Test-only seam: restores the singleton to its uninitialized state. */
    internal fun resetForTest() {
        recorder = null
        consentEnabled = false
        eventSink = null
        uploadScheduler = null
    }

    private fun telemetryQueue(context: Context) = TelemetryQueue(
        directory = File(context.filesDir, TELEMETRY_QUEUE_DIRECTORY),
        plane = TelemetryPlane.RESEARCH,
        clock = System::currentTimeMillis,
        limits = QueueLimits(),
    )

    private fun buildEnvelope(context: Context, event: ResearchEvent): TelemetryEnvelope {
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
            eventId = when (event) {
                is ResearchEvent.TextSample -> UUID.randomUUID().toString()
                is ResearchEvent.GestureTrace -> event.trace.traceId
                is ResearchEvent.Correction -> event.record.correctionId
            },
            batchId = UUID.randomUUID().toString(),
            installationId = installation?.installationId ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            occurredAtMs = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidApi = Build.VERSION.SDK_INT,
            eventType = ResearchEventCodec.eventType(event),
            payload = ResearchEventCodec.payload(event),
        )
    }
}

/**
 * Converts a finalized in-memory [ResearchTrace] into the bounded wire type, or null when it
 * cannot satisfy the wire bounds - the trace is dropped rather than truncated or sent out of
 * bounds.
 */
internal fun boundedTrace(trace: ResearchTrace): BoundedResearchTrace? = try {
    BoundedResearchTrace(
        traceId = trace.traceId,
        classification = trace.classification,
        language = trace.language,
        layoutId = trace.layoutId,
        algorithmVersion = trace.algorithmVersion,
        points = trace.points,
    )
} catch (_: IllegalArgumentException) {
    null
}
