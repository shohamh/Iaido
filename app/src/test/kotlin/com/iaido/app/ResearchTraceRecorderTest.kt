package com.iaido.app

import android.view.MotionEvent
import com.iaido.core.language.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

class ResearchTraceRecorderTest {
    @Test
    fun `trace stores normalized quantized points and preserves pointer ids`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 100L, 1000f, 500f, listOf(TouchPointer(4, 250f, 125f))))
        val trace = recorder.finish("swipe", Language.ENGLISH, "qwerty")!!
        assertEquals(0.25f, trace.points.single().x)
        assertEquals(0.25f, trace.points.single().y)
        assertEquals(4, trace.points.single().pointerId)
    }

    @Test
    fun `disabled or oversized traces are dropped`() {
        val recorder = ResearchTraceRecorder(enabled = { false })
        recorder.consume(sampleTouchFrame())
        assertNull(recorder.finish("swipe", Language.ENGLISH, "qwerty"))
    }

    @Test
    fun `quantization rounds to the nearest of three decimal digits deterministically`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        // On a 3px-wide/tall surface: x=1/3=0.3333... rounds down to 0.333, y=2/3=0.6666...
        // rounds up to 0.667 - exercising both rounding directions without landing on an exact
        // floating-point tie (which would make the expected rounding direction ambiguous).
        recorder.consume(
            TouchFrame(
                MotionEvent.ACTION_DOWN,
                0L,
                3f,
                3f,
                listOf(TouchPointer(0, 1f, 2f)),
            ),
        )
        val trace = recorder.finish("swipe", Language.ENGLISH, "qwerty")!!
        val point = trace.points.single()
        assertEquals(0.333f, point.x)
        assertEquals(0.667f, point.y)
    }

    @Test
    fun `multiple pointers within one frame preserve action ordering`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        recorder.consume(
            TouchFrame(
                MotionEvent.ACTION_MOVE,
                50L,
                200f,
                200f,
                listOf(TouchPointer(1, 20f, 20f), TouchPointer(2, 180f, 180f)),
            ),
        )
        val trace = recorder.finish("split", Language.ENGLISH, "qwerty")!!
        assertEquals(listOf(1, 2), trace.points.map { it.pointerId })
        assertEquals(listOf(0.1f, 0.9f), trace.points.map { it.x })
    }

    @Test
    fun `event times are stored relative to trace start, not absolute`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 5_000L, 100f, 100f, listOf(TouchPointer(0, 10f, 10f))))
        recorder.consume(TouchFrame(MotionEvent.ACTION_MOVE, 5_120L, 100f, 100f, listOf(TouchPointer(0, 20f, 20f))))
        recorder.consume(TouchFrame(MotionEvent.ACTION_UP, 5_240L, 100f, 100f, listOf(TouchPointer(0, 30f, 30f))))
        val trace = recorder.finish("swipe", Language.ENGLISH, "qwerty")!!
        assertEquals(listOf(0L, 120L, 240L), trace.points.map { it.timeOffsetMs })
    }

    @Test
    fun `a cancelled gesture is marked distinctly and not folded into a swipe example`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        recorder.consume(sampleTouchFrame())
        val trace = recorder.finish(ResearchTraceClassification.CANCELLED, Language.ENGLISH, "qwerty")!!
        assertEquals(ResearchTraceClassification.CANCELLED, trace.classification)
        assertNotEqualsSwipe(trace.classification)
    }

    @Test
    fun `a command gesture is marked distinctly from a swipe`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        recorder.consume(sampleTouchFrame())
        val trace = recorder.finish(ResearchTraceClassification.COMMAND, Language.ENGLISH, "qwerty")!!
        assertEquals(ResearchTraceClassification.COMMAND, trace.classification)
    }

    @Test
    fun `a trace exceeding the point-count cap is dropped entirely`() {
        val recorder = ResearchTraceRecorder(enabled = { true }, limits = ResearchTraceLimits(maxPoints = 2))
        repeat(5) { index ->
            recorder.consume(
                TouchFrame(MotionEvent.ACTION_MOVE, index * 10L, 100f, 100f, listOf(TouchPointer(0, index.toFloat(), index.toFloat()))),
            )
        }
        assertNull(recorder.finish("swipe", Language.ENGLISH, "qwerty"))
    }

    @Test
    fun `a trace exceeding the duration cap is dropped entirely`() {
        val recorder = ResearchTraceRecorder(enabled = { true }, limits = ResearchTraceLimits(maxDurationMs = 100L))
        recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 0L, 100f, 100f, listOf(TouchPointer(0, 0f, 0f))))
        recorder.consume(TouchFrame(MotionEvent.ACTION_MOVE, 5_000L, 100f, 100f, listOf(TouchPointer(0, 10f, 10f))))
        assertNull(recorder.finish("swipe", Language.ENGLISH, "qwerty"))
    }

    @Test
    fun `a trace within the byte cap succeeds while one that would exceed it is dropped`() {
        val withinCap = ResearchTraceRecorder(
            enabled = { true },
            limits = ResearchTraceLimits(maxPoints = 100, maxBytes = ResearchTraceRecorder.BYTES_PER_POINT * 3),
        )
        repeat(3) { index ->
            withinCap.consume(
                TouchFrame(MotionEvent.ACTION_MOVE, index * 10L, 100f, 100f, listOf(TouchPointer(0, index.toFloat(), index.toFloat()))),
            )
        }
        assertNotNull(withinCap.finish("swipe", Language.ENGLISH, "qwerty"))

        val overCap = ResearchTraceRecorder(
            enabled = { true },
            limits = ResearchTraceLimits(maxPoints = 100, maxBytes = ResearchTraceRecorder.BYTES_PER_POINT * 3),
        )
        repeat(4) { index ->
            overCap.consume(
                TouchFrame(MotionEvent.ACTION_MOVE, index * 10L, 100f, 100f, listOf(TouchPointer(0, index.toFloat(), index.toFloat()))),
            )
        }
        assertNull(overCap.finish("swipe", Language.ENGLISH, "qwerty"))
    }

    @Test
    fun `finish resets state so the next gesture starts a fresh trace`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 1_000L, 100f, 100f, listOf(TouchPointer(0, 10f, 10f))))
        recorder.finish("swipe", Language.ENGLISH, "qwerty")

        recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 9_000L, 100f, 100f, listOf(TouchPointer(0, 20f, 20f))))
        val trace = recorder.finish("swipe", Language.ENGLISH, "qwerty")!!
        assertEquals(0L, trace.points.single().timeOffsetMs)
    }

    @Test
    fun `finished trace includes layout, language, and algorithm version but never absolute surface size`() {
        val recorder = ResearchTraceRecorder(enabled = { true }, algorithmVersion = 7)
        recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 0L, 1080f, 2400f, listOf(TouchPointer(0, 100f, 200f))))
        val trace = recorder.finish("swipe", Language.HEBREW, "hebrew")!!
        assertEquals(Language.HEBREW, trace.language)
        assertEquals("hebrew", trace.layoutId)
        assertEquals(7, trace.algorithmVersion)
        // ResearchTrace's shape structurally excludes surface width/height fields; points are
        // normalized [0, 1] only.
        assertTrue(trace.points.all { it.x in 0f..1f && it.y in 0f..1f })
    }

    @Test
    fun `consume never throws even with degenerate surface dimensions`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        assertDoesNotThrow {
            recorder.consume(TouchFrame(MotionEvent.ACTION_DOWN, 0L, 0f, 0f, listOf(TouchPointer(0, 0f, 0f))))
            recorder.consume(TouchFrame(MotionEvent.ACTION_MOVE, 1L, Float.NaN, 100f, listOf(TouchPointer(0, 1f, 1f))))
            recorder.consume(TouchFrame(MotionEvent.ACTION_UP, 2L, -100f, 100f, listOf(TouchPointer(0, 1f, 1f))))
        }
        assertNull(recorder.finish("swipe", Language.ENGLISH, "qwerty"))
    }

    @Test
    fun `a failed callback still resets internal state and never throws`() {
        val recorder = ResearchTraceRecorder(enabled = { true })
        recorder.consume(sampleTouchFrame())
        assertDoesNotThrow {
            recorder.finish(ResearchTraceClassification.FAILED, Language.ENGLISH, "qwerty")
        }
    }

    @Test
    fun `each finished trace carries its own trace id for correction correlation`() {
        val recorder = ResearchTraceRecorder(enabled = { true })

        recorder.consume(sampleTouchFrame())
        val first = recorder.finish(ResearchTraceClassification.SWIPE, Language.ENGLISH, "qwerty")!!
        recorder.consume(sampleTouchFrame())
        val second = recorder.finish(ResearchTraceClassification.SWIPE, Language.ENGLISH, "qwerty")!!

        assertTrue(first.traceId.isNotBlank())
        assertTrue(second.traceId.isNotBlank())
        assertTrue(first.traceId != second.traceId)
    }

    @Test
    fun `isEnabled mirrors consent so the raw touch path can skip frame allocation`() {
        var consent = false
        val recorder = ResearchTraceRecorder(enabled = { consent })

        assertFalse(recorder.isEnabled)
        consent = true
        assertTrue(recorder.isEnabled)
    }

    private fun assertNotEqualsSwipe(classification: String) {
        assertTrue(classification != ResearchTraceClassification.SWIPE)
    }

    private fun sampleTouchFrame() = TouchFrame(
        MotionEvent.ACTION_DOWN,
        100L,
        1000f,
        500f,
        listOf(TouchPointer(0, 250f, 125f)),
    )
}
