package com.iaido.core.recognition

import com.iaido.core.gesture.GesturePoint
import com.iaido.core.gesture.GesturePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitGestureSessionTest {
    private fun point(x: Float, atMs: Long) = GesturePoint(x, 0f, atMs)

    @Test
    fun `parts are emitted in touch down order after the grace window`() {
        val session = SplitGestureSession(graceWindowMs = 350)
        session.begin(2, point(2f, 100), 100)
        session.begin(1, point(1f, 120), 120)
        session.end(1, 200, "ere")
        session.end(2, 220, "th")

        assertEquals(null, session.poll(569))
        val parts = session.poll(570) ?: error("completed poll must emit split parts")
        assertEquals(listOf("th", "ere"), parts.parts)
        assertEquals(listOf(100L, 120L), parts.touchDownAtMs)
        assertEquals(350L, parts.graceWindowMs)
    }

    @Test
    fun `emitted parts retain the grace window captured when pending begins`() {
        val session = SplitGestureSession(graceWindowMs = 350)
        session.begin(1, point(1f, 0), 0)
        session.end(1, 10, "th")
        session.setGraceWindowMs(100)

        val parts = session.poll(360)

        assertEquals(listOf("th"), parts?.parts)
        assertEquals(350L, parts?.graceWindowMs)
    }

    @Test
    fun `path points are retained with each completed part`() {
        val session = SplitGestureSession()
        session.begin(1, point(1f, 0), 0)
        session.move(1, point(2f, 10))
        session.end(1, 20, "th")

        assertEquals(listOf(point(1f, 0), point(2f, 10)), session.poll(370)?.paths?.single()?.points)
    }

    @Test
    fun `poll remains pending while another pointer is still active`() {
        val session = SplitGestureSession()
        session.begin(1, point(1f, 0), 0)
        session.begin(2, point(2f, 10), 10)
        session.end(1, 20, "th")

        assertEquals(null, session.poll(400))
        assertTrue(session.isPending())

        session.end(2, 450, "ere")
        assertEquals(listOf("th", "ere"), session.poll(800)?.parts)
        assertFalse(session.isPending())
    }

    @Test
    fun `tap during grace extends the latest part but outside it is rejected`() {
        val session = SplitGestureSession()
        session.begin(1, point(1f, 0), 0)
        session.end(1, 10, "I")

        assertTrue(session.tap(2, 'l', 100))
        assertEquals(listOf("Il"), session.poll(360)?.parts)
        assertFalse(session.tap(3, 'x', 400))
    }

    @Test
    fun `cancel and a late new touch do not reuse the previous word`() {
        val session = SplitGestureSession()
        session.begin(1, point(1f, 0), 0)
        session.end(1, 10, "old")
        session.cancel()
        assertEquals(null, session.poll(1_000))

        session.begin(2, point(2f, 2_000), 2_000)
        session.end(2, 2_010, "new")

        assertEquals(listOf("new"), session.poll(2_360)?.parts)
    }

    @Test
    fun `split word parts reject timestamp counts that do not align with paths`() {
        val malformed = assertThrows(IllegalArgumentException::class.java) {
            SplitWordParts(
                parts = listOf("a", "b"),
                paths = listOf(GesturePath(listOf(point(0f, 0)))),
                touchDownAtMs = listOf(0L),
                graceWindowMs = 350L,
            )
        }

        assertEquals("Parts, paths, and touch-down timestamps must align", malformed.message)
    }
}
