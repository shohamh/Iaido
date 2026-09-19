package com.iaido.app

import android.content.Context
import android.os.Build
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Coarse gesture family recorded for diagnostics. No coordinates or paths ever appear here. */
enum class DiagnosticsGestureKind {
    SWIPE,
    SPLIT,
    PUNCTUATION,
    COMMAND,
}

/** Kind of in-memory breadcrumb kept for crash context. */
enum class DiagnosticsBreadcrumbKind {
    APP_START,
    IME_SESSION_START,
    IME_SESSION_FINISH,
    GESTURE_OUTCOME,
    RECOGNITION_LATENCY,
    SUGGESTION_ACTION,
    RUNTIME_ERROR,
}

/** Suggestion/correction action family. Never carries the suggested text itself. */
enum class DiagnosticsSuggestionAction {
    REPLACEMENT,
    SUGGESTION_PICK,
    UNDO,
}

/** Redacted, enum-only runtime error classification. */
enum class DiagnosticsRuntimeErrorCode {
    RECOGNITION_FAILED,
    CORRECTION_FAILED,
    COMMAND_FAILED,
    SETTINGS_FAILED,
    UNKNOWN,
}

/** Coarse recognition-latency bucket. Never the raw millisecond value. */
enum class RecognitionLatencyBucket {
    UNDER_50_MS,
    FROM_50_TO_99_MS,
    FROM_100_TO_249_MS,
    FROM_250_TO_499_MS,
    FROM_500_TO_999_MS,
    OVER_1000_MS,
    ;

    companion object {
        fun forMillis(latencyMs: Long): RecognitionLatencyBucket = when {
            latencyMs < 50L -> UNDER_50_MS
            latencyMs < 100L -> FROM_50_TO_99_MS
            latencyMs < 250L -> FROM_100_TO_249_MS
            latencyMs < 500L -> FROM_250_TO_499_MS
            latencyMs < 1_000L -> FROM_500_TO_999_MS
            else -> OVER_1000_MS
        }
    }
}

/**
 * A single bounded, typed breadcrumb. Every field is an enum, a count, or an already-redacted
 * error, never editor text, candidate strings, or raw coordinates.
 */
data class DiagnosticsBreadcrumb(
    val kind: DiagnosticsBreadcrumbKind,
    val count: Int = 1,
    val gestureKind: DiagnosticsGestureKind? = null,
    val outcome: DiagnosticsOutcome? = null,
    val latencyBucket: RecognitionLatencyBucket? = null,
    val suggestionAction: DiagnosticsSuggestionAction? = null,
    val errorCode: DiagnosticsRuntimeErrorCode? = null,
    val error: RedactedThrowable? = null,
)

/**
 * Opt-in diagnostics capture: a bounded in-memory breadcrumb ring plus a small set of semantic
 * event helpers that serialize through the existing [DiagnosticsEvent] contract.
 *
 * Every public method is a no-op when diagnostics consent is disabled, and no method may ever
 * throw, telemetry failures must never affect keyboard behavior.
 */
class DiagnosticsTelemetry(
    private val diagnosticsEnabled: () -> Boolean,
    private val appendToQueue: (TelemetryEnvelope) -> Unit,
    private val scheduleUpload: () -> Unit,
    private val envelopeFactory: (DiagnosticsEvent) -> TelemetryEnvelope,
    private val maxBreadcrumbs: Int = DEFAULT_MAX_BREADCRUMBS,
) {
    private val ring = ArrayDeque<DiagnosticsBreadcrumb>()
    private val pendingEvents = mutableListOf<DiagnosticsEvent>()

    /** Records a raw diagnostics event for the next [flush]. No-op unless consent is enabled. */
    fun record(event: DiagnosticsEvent) {
        runSafely {
            if (!diagnosticsEnabled()) return@runSafely
            pendingEvents.add(event)
        }
    }

    fun recordAppStart() {
        addBreadcrumb(DiagnosticsBreadcrumb(kind = DiagnosticsBreadcrumbKind.APP_START))
    }

    fun recordImeSessionStart() {
        addBreadcrumb(DiagnosticsBreadcrumb(kind = DiagnosticsBreadcrumbKind.IME_SESSION_START))
    }

    fun recordImeSessionFinish() {
        addBreadcrumb(DiagnosticsBreadcrumb(kind = DiagnosticsBreadcrumbKind.IME_SESSION_FINISH))
    }

    fun recordGesture(kind: DiagnosticsGestureKind, outcome: DiagnosticsOutcome) {
        addBreadcrumb(
            DiagnosticsBreadcrumb(
                kind = DiagnosticsBreadcrumbKind.GESTURE_OUTCOME,
                gestureKind = kind,
                outcome = outcome,
            ),
        )
        record(DiagnosticsEvent.GestureOutcome(outcome = outcome))
    }

    fun recordRecognitionLatency(latencyMs: Long) {
        addBreadcrumb(
            DiagnosticsBreadcrumb(
                kind = DiagnosticsBreadcrumbKind.RECOGNITION_LATENCY,
                latencyBucket = RecognitionLatencyBucket.forMillis(latencyMs),
            ),
        )
    }

    fun recordSuggestionAction(action: DiagnosticsSuggestionAction, outcome: DiagnosticsOutcome) {
        addBreadcrumb(
            DiagnosticsBreadcrumb(
                kind = DiagnosticsBreadcrumbKind.SUGGESTION_ACTION,
                suggestionAction = action,
                outcome = outcome,
            ),
        )
    }

    fun recordRuntimeError(code: DiagnosticsRuntimeErrorCode, throwable: Throwable) {
        addBreadcrumb(
            DiagnosticsBreadcrumb(
                kind = DiagnosticsBreadcrumbKind.RUNTIME_ERROR,
                errorCode = code,
                error = redactThrowable(throwable),
            ),
        )
    }

    /** Snapshot of the current bounded breadcrumb ring, oldest first. */
    fun breadcrumbs(): List<DiagnosticsBreadcrumb> = runSafelyOrDefault(emptyList()) { ring.toList() }

    /** Rolls any pending events into the diagnostics queue and schedules upload work. */
    fun flush() {
        runSafely {
            if (!diagnosticsEnabled()) return@runSafely
            val events = pendingEvents.toList()
            if (events.isEmpty()) return@runSafely
            pendingEvents.clear()
            events.forEach { event -> appendToQueue(envelopeFactory(event)) }
            scheduleUpload()
        }
    }

    private fun addBreadcrumb(breadcrumb: DiagnosticsBreadcrumb) {
        runSafely {
            if (!diagnosticsEnabled()) return@runSafely
            ring.addLast(breadcrumb)
            while (ring.size > maxBreadcrumbs) ring.removeFirst()
        }
    }

    private fun runSafely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // Diagnostics telemetry is best effort and must never affect keyboard behavior.
        }
    }

    private fun <T> runSafelyOrDefault(default: T, block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        default
    }

    companion object {
        const val DEFAULT_MAX_BREADCRUMBS = 32
    }
}

/**
 * Process-wide access point for the single [DiagnosticsTelemetry] instance used by the app and
 * IME service. [initialize] is safe to call multiple times and from any process, but only ever
 * installs a live instance once (callers gate the call to the default application process).
 */
object DiagnosticsTelemetryProvider {
    private const val TELEMETRY_QUEUE_DIRECTORY = "telemetry"

    @Volatile
    private var telemetry: DiagnosticsTelemetry? = null

    @Volatile
    private var consentEnabled: Boolean = false

    private val sessionId = UUID.randomUUID().toString()

    val instance: DiagnosticsTelemetry?
        get() = telemetry

    /** Whether diagnostics consent is currently enabled, for use by the crash handler. */
    fun isEnabled(): Boolean = consentEnabled

    fun initialize(context: Context) {
        if (telemetry != null) return
        val appContext = context.applicationContext

        telemetry = DiagnosticsTelemetry(
            diagnosticsEnabled = ::isEnabled,
            appendToQueue = { envelope -> telemetryQueue(appContext).append(envelope) },
            scheduleUpload = { TelemetryUploadScheduler.schedule(appContext, TelemetryPlane.DIAGNOSTICS) },
            envelopeFactory = { event -> buildEnvelope(appContext, event) },
        )

        runCatching {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                runCatching {
                    appContext.settingsStore.data.collect { preferences ->
                        consentEnabled = diagnosticsConsentFromPreferences(preferences).enabled
                    }
                }
            }
        }
    }

    private fun telemetryQueue(context: Context) = TelemetryQueue(
        directory = File(context.filesDir, TELEMETRY_QUEUE_DIRECTORY),
        plane = TelemetryPlane.DIAGNOSTICS,
        clock = System::currentTimeMillis,
        limits = QueueLimits(),
    )

    private fun buildEnvelope(context: Context, event: DiagnosticsEvent): TelemetryEnvelope {
        val installation = runCatching {
            TelemetryInstallationStore(context).getOrCreateInstallation {
                TelemetryInstallation(
                    installationId = UUID.randomUUID().toString(),
                    writeCredential = UUID.randomUUID().toString(),
                    deletionCredential = UUID.randomUUID().toString(),
                )
            }
        }.getOrNull()
        val payload = Json.parseToJsonElement(DiagnosticsEventCodec.encode(event)) as JsonObject
        return TelemetryEnvelope(
            schemaVersion = TelemetryEnvelope.CURRENT_SCHEMA_VERSION,
            eventId = UUID.randomUUID().toString(),
            batchId = UUID.randomUUID().toString(),
            installationId = installation?.installationId ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            occurredAtMs = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidApi = Build.VERSION.SDK_INT,
            eventType = "gesture_outcome",
            payload = payload,
        )
    }
}
