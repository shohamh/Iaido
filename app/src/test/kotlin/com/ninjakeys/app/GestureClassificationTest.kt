package com.ninjakeys.app

import com.ninjakeys.core.gesture.GesturePoint
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GestureClassificationTest {
    @Test
    fun `down and up at one location is a tap`() {
        val point = GesturePoint(100f, 200f, 0L)

        assertTrue(isTapGesture(listOf(point, point.copy(timestampMs = 16L)), 128f))
    }

    @Test
    fun `movement across keys is a swipe`() {
        val points = listOf(
            GesturePoint(100f, 200f, 0L),
            GesturePoint(240f, 200f, 16L),
        )

        assertFalse(isTapGesture(points, 128f))
    }

    @Test
    fun `a normal cross-row word swipe is not an upward flick`() {
        val points = listOf(
            GesturePoint(896f, 320f, 0L),
            GesturePoint(960f, 64f, 16L),
            GesturePoint(896f, 320f, 32L),
            GesturePoint(896f, 192f, 48L),
            GesturePoint(128f, 192f, 64L),
        )

        assertFalse(isUpwardFlickGesture(points, 128f))
    }

    @Test
    fun `a short mostly vertical upward movement is an upward flick`() {
        val points = listOf(
            GesturePoint(128f, 192f, 0L),
            GesturePoint(132f, 112f, 16L),
        )

        assertTrue(isUpwardFlickGesture(points, 128f))
    }
}
