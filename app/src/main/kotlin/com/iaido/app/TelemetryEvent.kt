package com.iaido.app

import com.iaido.core.language.Language
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class TelemetryEnvelope(
    val schemaVersion: Int,
    val eventId: String,
    val batchId: String,
    val installationId: String,
    val sessionId: String,
    val occurredAtMs: Long,
    val appVersion: String,
    val buildType: String,
    val androidApi: Int,
    val eventType: String,
    val payload: JsonObject,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported telemetry envelope version: $schemaVersion"
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

enum class DiagnosticsOutcome {
    ACCEPTED,
    REJECTED,
    CANCELLED,
}

data class BoundedResearchText(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH) {
            "Research text must contain at most $MAX_LENGTH characters"
        }
    }

    companion object {
        const val MAX_LENGTH = 256
    }
}

/**
 * Discrete correction actions a caller can hand to `ResearchCorrectionRecorder.record`. Wire names
 * are the lowercased enum name, e.g. [CANDIDATE_SELECTED] serializes as "candidate_selected".
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
 * Explicit gesture-family tags passed to `ResearchTraceRecorder.finish`. Using one of these (rather
 * than an ad-hoc string) keeps a cancelled, failed, or non-typing gesture (a command, a
 * punctuation-to-space, a backspace edit) from ever being silently indistinguishable from a real
 * swipe-training example.
 */
object ResearchTraceClassification {
    const val SWIPE = "swipe"
    const val TAP = "tap"
    const val FLICK = "flick"
    const val SPLIT = "split"
    const val COMMAND = "command"
    const val PUNCTUATION = "punctuation"
    const val BACKSPACE = "backspace"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val ALL = setOf(SWIPE, TAP, FLICK, SPLIT, COMMAND, PUNCTUATION, BACKSPACE, FAILED, CANCELLED)

    /** Classifications that commit a recognized word, and so can own a correction record. */
    val TYPING = setOf(SWIPE, SPLIT)
}

/**
 * One normalized, quantized pointer sample inside a [BoundedResearchTrace]. Coordinates are
 * relative to the capturing keyboard surface, and [timeOffsetMs] is relative to the trace's first
 * sample - never an absolute wall-clock timestamp.
 */
data class ResearchTraceSample(
    val pointerId: Int,
    val action: Int,
    val timeOffsetMs: Long,
    val x: Float,
    val y: Float,
) {
    init {
        require(pointerId >= 0) { "Trace pointer ids must not be negative" }
        require(action >= 0) { "Trace pointer actions must not be negative" }
        require(timeOffsetMs >= 0) { "Trace pointer times must be relative to the trace start" }
        require(x.isFinite() && y.isFinite()) { "Keyboard pointer coordinates must be finite" }
        require(x in 0f..1f && y in 0f..1f) {
            "Keyboard pointer coordinates must be normalized to [0, 1]"
        }
    }
}

/**
 * Bounded, normalized gesture trace as it crosses the wire on the research plane. Construction
 * enforces every bound the server also enforces, so an out-of-range trace cannot be serialized.
 */
data class BoundedResearchTrace(
    val traceId: String,
    val classification: String,
    val language: Language,
    val layoutId: String,
    val algorithmVersion: Int,
    val points: List<ResearchTraceSample>,
) {
    init {
        require(traceId.isNotBlank()) { "Trace id must not be blank" }
        require(classification in ResearchTraceClassification.ALL) {
            "Unsupported trace classification: $classification"
        }
        require(layoutId.isNotBlank()) { "Trace layout id must not be blank" }
        require(algorithmVersion >= 1) { "Algorithm versions start at 1" }
        require(points.isNotEmpty() && points.size <= MAX_POINTS) {
            "Gesture traces must contain 1-$MAX_POINTS pointers"
        }
        require(points.zipWithNext().all { (earlier, later) -> later.timeOffsetMs >= earlier.timeOffsetMs }) {
            "Trace pointer times must be monotonic"
        }
    }

    companion object {
        const val MAX_POINTS = 512
    }
}

/**
 * Bounded correction example as it crosses the wire on the research plane: only the affected
 * source/final span, never surrounding sentence or document context. [traceId] is the gesture
 * trace this correction belongs to, or null when no trace was captured for that gesture.
 */
data class BoundedResearchCorrection(
    val correctionId: String,
    val traceId: String?,
    val action: CorrectionAction,
    val sourceText: String,
    val finalText: String,
    val candidates: List<String>,
    val algorithmVersion: Int,
) {
    init {
        require(correctionId.isNotBlank()) { "Correction id must not be blank" }
        require(traceId == null || traceId.isNotBlank()) { "Trace id must be absent or non-blank" }
        require(algorithmVersion >= 1) { "Algorithm versions start at 1" }
        require(boundedResearchSpan(sourceText) == sourceText) {
            "Correction source span must be 1-$MAX_SPAN_CODE_POINTS normalized code points"
        }
        require(boundedResearchSpan(finalText) == finalText) {
            "Correction final span must be 1-$MAX_SPAN_CODE_POINTS normalized code points"
        }
        require(candidates.size <= MAX_CANDIDATES) {
            "Corrections carry at most $MAX_CANDIDATES candidates"
        }
        require(candidates.all { boundedResearchSpan(it) == it }) {
            "Correction candidates must be 1-$MAX_SPAN_CODE_POINTS normalized code points"
        }
    }

    companion object {
        const val MAX_CANDIDATES = 5
    }
}

/** Hard cap on one affected source/final span, in Unicode code points. */
internal const val MAX_SPAN_CODE_POINTS = 64

/**
 * Normalizes [text] to NFC and returns it, or null when it is empty or exceeds
 * [MAX_SPAN_CODE_POINTS] code points - callers reject the whole span rather than truncating it
 * into a misleading partial example.
 */
internal fun boundedResearchSpan(text: String): String? {
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
    if (normalized.isEmpty()) return null
    if (normalized.codePointCount(0, normalized.length) > MAX_SPAN_CODE_POINTS) return null
    return normalized
}

/** Coarse gesture family recorded for diagnostics. No coordinates or paths ever appear here. */
enum class DiagnosticsGestureKind {
    SWIPE,
    SPLIT,
    PUNCTUATION,
    COMMAND,
}

/** Kind of in-memory breadcrumb kept as crash context. */
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

/** Hard caps shared with the server's `iaido_telemetry.schemas` bounds. */
internal const val MAX_ERROR_TYPE_LENGTH = 64
internal const val MAX_ERROR_FRAMES = 16
internal const val MAX_ERROR_FRAME_LENGTH = 160
internal const val MAX_BREADCRUMBS = 32
internal const val MAX_BREADCRUMB_COUNT = 10_000

/**
 * A stack frame as it crosses the wire: `Class.method(File.kt:123)` or the `<redacted>`
 * placeholder. Directories are never included, so a frame cannot carry a developer or user path;
 * the pattern is enforced at construction so a frame the server would reject cannot be built.
 */
internal val ERROR_FRAME_PATTERN =
    Regex("""^(?:<redacted>|[A-Za-z0-9_.$<>]+\([A-Za-z0-9_.$]*:[0-9]{1,7}\))$""")

/**
 * Collected exception shape for diagnostics: the class name and bounded stack frames. There is
 * deliberately no message field - exception messages can embed typed text, which the diagnostics
 * contract excludes - so the type plus frames are what a reader gets.
 */
data class RedactedThrowable(
    val type: String,
    val frames: List<String>,
) {
    init {
        require(type.isNotBlank() && type.length <= MAX_ERROR_TYPE_LENGTH) {
            "Exception types must be 1-$MAX_ERROR_TYPE_LENGTH characters"
        }
        require(type.all { it.isLetterOrDigit() || it in "_.$<>" }) {
            "Exception types must be simple class names"
        }
        require(frames.size <= MAX_ERROR_FRAMES) {
            "Stack traces carry at most $MAX_ERROR_FRAMES frames"
        }
        require(frames.all { it.matches(ERROR_FRAME_PATTERN) }) {
            "Stack frames must be Class.method(File:line)"
        }
    }
}

/**
 * A single bounded, typed breadcrumb kept as crash context. Every field is an enum, a count, or an
 * already-redacted error, never editor text, candidate strings, or raw coordinates.
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
) {
    init {
        require(count in 1..MAX_BREADCRUMB_COUNT) {
            "Breadcrumb counts must be 1-$MAX_BREADCRUMB_COUNT"
        }
    }
}

sealed interface DiagnosticsEvent {
    data class GestureOutcome(val outcome: DiagnosticsOutcome) : DiagnosticsEvent

    /** A recoverable runtime failure: a stable code plus the redacted exception shape. */
    data class RuntimeError(
        val code: DiagnosticsRuntimeErrorCode,
        val error: RedactedThrowable,
    ) : DiagnosticsEvent

    /** An uncaught crash, replayed from the crash envelope written by the crash handler. */
    data class Crash(
        val error: RedactedThrowable,
        val breadcrumbs: List<DiagnosticsBreadcrumb> = emptyList(),
    ) : DiagnosticsEvent {
        init {
            require(breadcrumbs.size <= MAX_BREADCRUMBS) {
                "Crash events carry at most $MAX_BREADCRUMBS breadcrumbs"
            }
        }
    }
}

sealed interface ResearchEvent {
    data class TextSample(val text: BoundedResearchText) : ResearchEvent
    data class GestureTrace(val trace: BoundedResearchTrace) : ResearchEvent
    data class Correction(val record: BoundedResearchCorrection) : ResearchEvent
}

object DiagnosticsEventCodec {
    const val GESTURE_OUTCOME = "gesture_outcome"
    const val RUNTIME_ERROR = "runtime_error"
    const val CRASH = "crash"

    private val json = Json

    /** Envelope event type for [event]; the envelope carries it, the payload repeats it. */
    fun eventType(event: DiagnosticsEvent): String = when (event) {
        is DiagnosticsEvent.GestureOutcome -> GESTURE_OUTCOME
        is DiagnosticsEvent.RuntimeError -> RUNTIME_ERROR
        is DiagnosticsEvent.Crash -> CRASH
    }

    fun encode(event: DiagnosticsEvent): String = when (event) {
        is DiagnosticsEvent.GestureOutcome -> buildJsonObject {
            put("event_type", JsonPrimitive(GESTURE_OUTCOME))
            put("outcome", JsonPrimitive(event.outcome.name))
        }.toString()

        is DiagnosticsEvent.RuntimeError -> buildJsonObject {
            put("event_type", JsonPrimitive(RUNTIME_ERROR))
            put("code", JsonPrimitive(event.code.name))
            put("error", encodeError(event.error))
        }.toString()

        is DiagnosticsEvent.Crash -> buildJsonObject {
            put("event_type", JsonPrimitive(CRASH))
            put("error", encodeError(event.error))
            put("breadcrumbs", buildJsonArray { event.breadcrumbs.forEach { add(encodeBreadcrumb(it)) } })
        }.toString()
    }

    fun decode(jsonText: String): DiagnosticsEvent {
        val root = try {
            json.parseToJsonElement(jsonText).let { element ->
                require(element is JsonObject) { "Diagnostics event must be a JSON object" }
                element
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid diagnostics event", error)
        }

        return when (val eventType = root.requiredString("event_type")) {
            GESTURE_OUTCOME -> {
                requireKeys(root, setOf("event_type", "outcome"))
                DiagnosticsEvent.GestureOutcome(root.enumValue("outcome", DiagnosticsOutcome.entries))
            }

            RUNTIME_ERROR -> {
                requireKeys(root, setOf("event_type", "code", "error"))
                DiagnosticsEvent.RuntimeError(
                    code = root.enumValue("code", DiagnosticsRuntimeErrorCode.entries),
                    error = decodeError(root.getValue("error").jsonObject),
                )
            }

            CRASH -> {
                requireKeys(root, setOf("event_type", "error", "breadcrumbs"))
                DiagnosticsEvent.Crash(
                    error = decodeError(root.getValue("error").jsonObject),
                    breadcrumbs = root.getValue("breadcrumbs").jsonArray.map { decodeBreadcrumb(it.jsonObject) },
                )
            }

            else -> throw IllegalArgumentException("Unsupported diagnostics event type: $eventType")
        }
    }

    private fun encodeError(error: RedactedThrowable) = buildJsonObject {
        put("type", JsonPrimitive(error.type))
        put("frames", buildJsonArray { error.frames.forEach { add(JsonPrimitive(it)) } })
    }

    private fun encodeBreadcrumb(crumb: DiagnosticsBreadcrumb) = buildJsonObject {
        put("kind", JsonPrimitive(crumb.kind.name))
        put("count", JsonPrimitive(crumb.count))
        crumb.gestureKind?.let { put("gesture_kind", JsonPrimitive(it.name)) }
        crumb.outcome?.let { put("outcome", JsonPrimitive(it.name)) }
        crumb.latencyBucket?.let { put("latency_bucket", JsonPrimitive(it.name)) }
        crumb.suggestionAction?.let { put("suggestion_action", JsonPrimitive(it.name)) }
        crumb.errorCode?.let { put("error_code", JsonPrimitive(it.name)) }
        crumb.error?.let { put("error", encodeError(it)) }
    }

    private fun decodeError(error: JsonObject): RedactedThrowable {
        requireKeys(error, setOf("type", "frames"))
        return RedactedThrowable(
            type = error.requiredString("type"),
            frames = error.getValue("frames").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun decodeBreadcrumb(crumb: JsonObject): DiagnosticsBreadcrumb {
        requireKeys(
            crumb,
            setOf("kind", "count"),
            optional = setOf(
                "gesture_kind",
                "outcome",
                "latency_bucket",
                "suggestion_action",
                "error_code",
                "error",
            ),
        )
        return DiagnosticsBreadcrumb(
            kind = crumb.enumValue("kind", DiagnosticsBreadcrumbKind.entries),
            count = crumb.getValue("count").jsonPrimitive.int,
            gestureKind = crumb.optionalEnum("gesture_kind", DiagnosticsGestureKind.entries),
            outcome = crumb.optionalEnum("outcome", DiagnosticsOutcome.entries),
            latencyBucket = crumb.optionalEnum("latency_bucket", RecognitionLatencyBucket.entries),
            suggestionAction = crumb.optionalEnum("suggestion_action", DiagnosticsSuggestionAction.entries),
            errorCode = crumb.optionalEnum("error_code", DiagnosticsRuntimeErrorCode.entries),
            error = crumb["error"]?.jsonObject?.let { decodeError(it) },
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing diagnostics field: $name")

    private fun <T : Enum<T>> JsonObject.enumValue(name: String, entries: List<T>): T {
        val raw = requiredString(name)
        return entries.firstOrNull { it.name == raw }
            ?: throw IllegalArgumentException("Unsupported diagnostics value for $name: $raw")
    }

    private fun <T : Enum<T>> JsonObject.optionalEnum(name: String, entries: List<T>): T? {
        if (!containsKey(name)) return null
        return enumValue(name, entries)
    }

    private fun requireKeys(
        payload: JsonObject,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        val unknown = payload.keys - required - optional
        require(unknown.isEmpty()) {
            "Diagnostics events cannot contain research text or trace fields: $unknown"
        }
        val missing = required - payload.keys
        require(missing.isEmpty()) { "Missing diagnostics fields: $missing" }
    }
}

/**
 * Wire codec for the research plane. Payloads never repeat `event_type` (the envelope carries it),
 * and decoding rejects unknown, missing, or out-of-bounds fields instead of dropping them - the
 * same bounds the server enforces in `iaido_telemetry.schemas`.
 */
object ResearchEventCodec {
    const val TEXT_SAMPLE = "text_sample"
    const val GESTURE_TRACE = "gesture_trace"
    const val RESEARCH_CORRECTION = "research_correction"

    fun eventType(event: ResearchEvent): String = when (event) {
        is ResearchEvent.TextSample -> TEXT_SAMPLE
        is ResearchEvent.GestureTrace -> GESTURE_TRACE
        is ResearchEvent.Correction -> RESEARCH_CORRECTION
    }

    fun payload(event: ResearchEvent): JsonObject = when (event) {
        is ResearchEvent.TextSample -> buildJsonObject {
            put("text", JsonPrimitive(event.text.value))
        }

        is ResearchEvent.GestureTrace -> buildJsonObject {
            put("trace_id", JsonPrimitive(event.trace.traceId))
            put("classification", JsonPrimitive(event.trace.classification))
            put("language", JsonPrimitive(event.trace.language.name))
            put("layout_id", JsonPrimitive(event.trace.layoutId))
            put("algorithm_version", JsonPrimitive(event.trace.algorithmVersion))
            put("points", buildJsonArray {
                event.trace.points.forEach { sample ->
                    add(
                        buildJsonObject {
                            put("pointer_id", JsonPrimitive(sample.pointerId))
                            put("action", JsonPrimitive(sample.action))
                            put("time_offset_ms", JsonPrimitive(sample.timeOffsetMs))
                            put("x", JsonPrimitive(sample.x))
                            put("y", JsonPrimitive(sample.y))
                        },
                    )
                }
            })
        }

        is ResearchEvent.Correction -> buildJsonObject {
            val record = event.record
            put("correction_id", JsonPrimitive(record.correctionId))
            record.traceId?.let { put("trace_id", JsonPrimitive(it)) }
            put("action", JsonPrimitive(record.action.wireName))
            put("source_text", JsonPrimitive(record.sourceText))
            put("final_text", JsonPrimitive(record.finalText))
            put("candidates", buildJsonArray { record.candidates.forEach { add(JsonPrimitive(it)) } })
            put("algorithm_version", JsonPrimitive(record.algorithmVersion))
        }
    }

    fun decode(eventType: String, payload: JsonObject): ResearchEvent = when (eventType) {
        TEXT_SAMPLE -> {
            requireKeys(payload, setOf("text"))
            ResearchEvent.TextSample(
                BoundedResearchText(payload.getValue("text").jsonPrimitive.content),
            )
        }

        GESTURE_TRACE -> ResearchEvent.GestureTrace(decodeTrace(payload))

        RESEARCH_CORRECTION -> ResearchEvent.Correction(decodeCorrection(payload))

        else -> throw IllegalArgumentException("Unsupported research event type: $eventType")
    }

    private fun decodeTrace(payload: JsonObject): BoundedResearchTrace {
        requireKeys(
            payload,
            setOf("trace_id", "classification", "language", "layout_id", "algorithm_version", "points"),
        )
        val languageName = payload.getValue("language").jsonPrimitive.content
        val language = Language.entries.firstOrNull { it.name == languageName }
            ?: throw IllegalArgumentException("Unsupported research language: $languageName")
        return BoundedResearchTrace(
            traceId = payload.getValue("trace_id").jsonPrimitive.content,
            classification = payload.getValue("classification").jsonPrimitive.content,
            language = language,
            layoutId = payload.getValue("layout_id").jsonPrimitive.content,
            algorithmVersion = payload.getValue("algorithm_version").jsonPrimitive.int,
            points = payload.getValue("points").jsonArray.map { element ->
                val point = element.jsonObject
                requireKeys(point, setOf("pointer_id", "action", "time_offset_ms", "x", "y"))
                ResearchTraceSample(
                    pointerId = point.getValue("pointer_id").jsonPrimitive.int,
                    action = point.getValue("action").jsonPrimitive.int,
                    timeOffsetMs = point.getValue("time_offset_ms").jsonPrimitive.long,
                    x = point.getValue("x").jsonPrimitive.float,
                    y = point.getValue("y").jsonPrimitive.float,
                )
            },
        )
    }

    private fun decodeCorrection(payload: JsonObject): BoundedResearchCorrection {
        requireKeys(
            payload,
            setOf("correction_id", "action", "source_text", "final_text", "candidates", "algorithm_version"),
            optional = setOf("trace_id"),
        )
        val actionName = payload.getValue("action").jsonPrimitive.content
        val action = CorrectionAction.entries.firstOrNull { it.wireName == actionName }
            ?: throw IllegalArgumentException("Unsupported correction action: $actionName")
        return BoundedResearchCorrection(
            correctionId = payload.getValue("correction_id").jsonPrimitive.content,
            traceId = payload["trace_id"]
                ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                ?.jsonPrimitive
                ?.contentOrNull,
            action = action,
            sourceText = payload.getValue("source_text").jsonPrimitive.content,
            finalText = payload.getValue("final_text").jsonPrimitive.content,
            candidates = payload.getValue("candidates").jsonArray.map { it.jsonPrimitive.content },
            algorithmVersion = payload.getValue("algorithm_version").jsonPrimitive.int,
        )
    }

    private fun requireKeys(
        payload: JsonObject,
        required: Set<String>,
        optional: Set<String> = emptySet(),
    ) {
        val unknown = payload.keys - required - optional
        require(unknown.isEmpty()) { "Unsupported research payload fields: $unknown" }
        val missing = required - payload.keys
        require(missing.isEmpty()) { "Missing research payload fields: $missing" }
    }
}
