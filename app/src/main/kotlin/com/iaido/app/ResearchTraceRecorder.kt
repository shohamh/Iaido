package com.iaido.app

import com.iaido.core.language.Language
import kotlin.math.roundToInt

/**
 * Immutable, already-redacted copy of one raw Android touch/pointer event, captured
 * synchronously at the touch boundary (see `KeyboardInputView`'s `pointerInteropFilter`). This
 * never wraps or retains the original `android.view.MotionEvent` - Android recycles those
 * objects once the callback that received them returns, so holding a reference beyond that
 * callback would be a use-after-recycle bug. [surfaceWidthPx]/[surfaceHeightPx] are the local
 * keyboard touch surface's own bounds (not the device screen or host-window size) - just enough
 * to normalize [pointers] into `[0, 1]`.
 */
data class TouchFrame(
    val action: Int,
    val eventTimeMs: Long,
    val surfaceWidthPx: Float,
    val surfaceHeightPx: Float,
    val pointers: List<TouchPointer>,
)

/** One pointer's raw pixel position within a [TouchFrame]. */
data class TouchPointer(
    val pointerId: Int,
    val xPx: Float,
    val yPx: Float,
)

/**
 * One normalized, quantized touch sample stored inside a finished [ResearchTrace]. [x]/[y] are
 * in `[0, 1]` relative to the capturing surface, and [timeOffsetMs] is relative to the trace's
 * first sample - never an absolute wall-clock timestamp.
 */
data class ResearchTracePoint(
    val pointerId: Int,
    val action: Int,
    val timeOffsetMs: Long,
    val x: Float,
    val y: Float,
)

/**
 * Bounded, finalized research trace returned once per completed gesture by
 * [ResearchTraceRecorder.finish]. Carries only what's needed to reproduce the gesture's shape
 * for research/training purposes plus the coarse context (language/layout/algorithm version) to
 * interpret it - never absolute screen size or any host-window/app data.
 */
data class ResearchTrace(
    val classification: String,
    val language: Language,
    val layoutId: String,
    val algorithmVersion: Int,
    val points: List<ResearchTracePoint>,
)

/**
 * Explicit gesture-family tags a caller passes to [ResearchTraceRecorder.finish]. Using one of
 * these (rather than an ad-hoc string) keeps a cancelled, failed, or non-typing gesture (a
 * command, a punctuation-to-space, a backspace edit) from ever being silently indistinguishable
 * from a real swipe-training example.
 */
object ResearchTraceClassification {
    const val SWIPE = "swipe"
    const val TAP = "tap"
    const val FLICK = "flick"
    const val SPLIT = "split"
    const val COMMAND = "command"
    const val PUNCTUATION = "punctuation"
    const val BACKSPACE = "backspace"
    const val CANCELLED = "cancelled"
    const val FAILED = "failed"
}

/**
 * Hard caps a single in-progress trace must stay within, or [ResearchTraceRecorder.finish] drops
 * it entirely (returns `null`) rather than truncating it into a misleading partial trace.
 *
 * Defaults are deliberately small relative to [QueueLimits] (a *batch* of many envelopes): a
 * single gesture trace is one envelope's payload, so [maxPoints]/[maxBytes] only need to bound
 * one swipe's worth of samples, not a whole batch.
 */
data class ResearchTraceLimits(
    val maxPoints: Int = ResearchTraceRecorder.DEFAULT_MAX_POINTS,
    val maxDurationMs: Long = ResearchTraceRecorder.DEFAULT_MAX_DURATION_MS,
    val maxBytes: Long = ResearchTraceRecorder.DEFAULT_MAX_BYTES,
) {
    init {
        require(maxPoints > 0) { "maxPoints must be positive" }
        require(maxDurationMs >= 0) { "maxDurationMs cannot be negative" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }
}

/**
 * Pure in-memory recorder for opt-in research touch traces.
 *
 * [consume] appends a normalized, quantized sample per pointer to an in-memory buffer only - it
 * must NEVER perform disk I/O, network calls, or touch [TelemetryQueue]/[TelemetryTransport]/
 * WorkManager. It runs on every raw touch/pointer-move event during a gesture (potentially dozens
 * of times per swipe), so any I/O here would stall the input thread on every single touch move -
 * the exact class of bug found and fixed in [DiagnosticsTelemetry] during Task 5's fix round.
 *
 * [finish] is the only point where a trace is finalized. It is called once per completed gesture
 * and returns a [ResearchTrace] value - it does not persist it anywhere. Handing the returned
 * trace to the telemetry queue/upload path is the caller's responsibility (the IME service,
 * wired separately).
 *
 * Every public method is defensive: consent is re-checked on every call, and nothing here may
 * ever throw into the caller - telemetry failures must never affect keyboard behavior.
 */
class ResearchTraceRecorder(
    private val enabled: () -> Boolean,
    private val limits: ResearchTraceLimits = ResearchTraceLimits(),
    private val algorithmVersion: Int = CURRENT_ALGORITHM_VERSION,
) {
    private var traceStartMs: Long? = null
    private val points = mutableListOf<ResearchTracePoint>()
    private var overCap = false

    /**
     * Appends [frame]'s pointers to the in-memory buffer for the trace currently in progress,
     * starting a new one if none is active. No-ops (does not throw) when research consent is
     * disabled, when [frame]'s surface dimensions are unusable, or once the trace has already
     * gone over a hard cap (further samples are dropped rather than grown without bound, and the
     * whole trace is rejected by [finish]).
     */
    fun consume(frame: TouchFrame) {
        try {
            if (!enabled()) return
            if (!frame.surfaceWidthPx.isFinite() || !frame.surfaceHeightPx.isFinite()) return
            if (frame.surfaceWidthPx <= 0f || frame.surfaceHeightPx <= 0f) return

            val startMs = traceStartMs ?: frame.eventTimeMs
            if (traceStartMs == null) traceStartMs = startMs
            val offsetMs = frame.eventTimeMs - startMs
            if (offsetMs < 0L || offsetMs > limits.maxDurationMs) {
                overCap = true
                return
            }

            frame.pointers.forEach { pointer ->
                if (overCap) return@forEach
                if (points.size >= limits.maxPoints || estimatedBytes(points.size + 1) > limits.maxBytes) {
                    overCap = true
                    return@forEach
                }
                val x = quantize(normalize(pointer.xPx, frame.surfaceWidthPx))
                val y = quantize(normalize(pointer.yPx, frame.surfaceHeightPx))
                if (x == null || y == null) return@forEach
                points.add(
                    ResearchTracePoint(
                        pointerId = pointer.pointerId,
                        action = frame.action,
                        timeOffsetMs = offsetMs,
                        x = x,
                        y = y,
                    ),
                )
            }
        } catch (_: Exception) {
            // Research trace capture is best effort and must never affect keyboard behavior.
        }
    }

    /**
     * Finalizes the trace currently in progress and resets internal state for the next gesture,
     * regardless of outcome. Returns `null` (dropping the trace) when research consent is
     * disabled, when no usable samples were captured, or when the trace went over a hard cap
     * during [consume] - never a partial/truncated trace. [classification] should be one of
     * [ResearchTraceClassification]'s constants so cancelled, failed, or non-typing gestures stay
     * explicitly distinguishable from a real swipe-training example.
     */
    fun finish(classification: String, language: Language, layoutId: String): ResearchTrace? {
        return try {
            if (!enabled() || overCap || points.isEmpty()) {
                null
            } else {
                ResearchTrace(
                    classification = classification,
                    language = language,
                    layoutId = layoutId,
                    algorithmVersion = algorithmVersion,
                    points = points.toList(),
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            reset()
        }
    }

    private fun reset() {
        traceStartMs = null
        points.clear()
        overCap = false
    }

    private fun normalize(valuePx: Float, dimensionPx: Float): Float =
        (valuePx / dimensionPx).coerceIn(0f, 1f)

    /**
     * Quantizes a normalized `[0, 1]` coordinate to a fixed precision of 1/[QUANTIZATION_SCALE]
     * (3 decimal digits, i.e. ~0.001 resolution - about 1px on a 1000px-wide surface, ample for
     * gesture-shape research while keeping stored values deterministic and small). Returns `null`
     * for a non-finite input rather than a garbage value.
     */
    private fun quantize(normalized: Float): Float? {
        if (!normalized.isFinite()) return null
        return (normalized * QUANTIZATION_SCALE).roundToInt() / QUANTIZATION_SCALE.toFloat()
    }

    private fun estimatedBytes(pointCount: Int): Long = pointCount.toLong() * BYTES_PER_POINT

    companion object {
        /** Quantization precision: 1/1000 of the normalized `[0, 1]` range (3 decimal digits). */
        const val QUANTIZATION_SCALE = 1000

        /**
         * Estimated bytes per stored [ResearchTracePoint]: pointerId (4) + action (4) +
         * timeOffsetMs (8) + x (4) + y (4) = 24 bytes. Used only to size [DEFAULT_MAX_BYTES] and
         * to defensively cap in-memory growth if more fields are ever added to the point shape.
         */
        const val BYTES_PER_POINT = 24L

        /** A single gesture rarely needs more samples than this to represent its shape. */
        const val DEFAULT_MAX_POINTS = 512

        /** Generous upper bound for one gesture's duration; well past any real swipe/split. */
        const val DEFAULT_MAX_DURATION_MS = 10_000L

        /** Matches [DEFAULT_MAX_POINTS] * [BYTES_PER_POINT] - a redundant, explicit byte cap. */
        const val DEFAULT_MAX_BYTES = DEFAULT_MAX_POINTS * BYTES_PER_POINT

        const val CURRENT_ALGORITHM_VERSION = 1
    }
}
