package com.ninjakeys.core.recognition

import com.ninjakeys.core.gesture.GesturePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        assertEquals(listOf("th", "ere"), session.poll(570)?.parts)
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
}
