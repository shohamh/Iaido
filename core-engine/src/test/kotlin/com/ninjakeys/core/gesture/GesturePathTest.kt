package com.ninjakeys.core.gesture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class GesturePathTest {

    @Test
    fun `resample produces exactly the requested number of points`() {
        val path = GesturePath(
            listOf(
                GesturePoint(0f, 0f, 0),
                GesturePoint(10f, 0f, 10),
                GesturePoint(10f, 10f, 20),
            )
        )

        val resampled = path.resample(5)

        assertEquals(5, resampled.points.size)
    }

    @Test
    fun `resample preserves start and end points`() {
        val path = GesturePath(
            listOf(
                GesturePoint(0f, 0f, 0),
                GesturePoint(10f, 0f, 10),
                GesturePoint(10f, 10f, 20),
            )
        )

        val resampled = path.resample(5)

        assertEquals(0f, resampled.points.first().x, 0.001f)
        assertEquals(0f, resampled.points.first().y, 0.001f)
        assertEquals(10f, resampled.points.last().x, 0.001f)
        assertEquals(10f, resampled.points.last().y, 0.001f)
    }

    @Test
    fun `resample evenly spaces points by arc length`() {
        // A straight horizontal line of length 20 resampled to 3 points
        // should land at x = 0, 10, 20.
        val path = GesturePath(
            listOf(
                GesturePoint(0f, 0f, 0),
                GesturePoint(20f, 0f, 20),
            )
        )

        val resampled = path.resample(3)

        assertEquals(0f, resampled.points[0].x, 0.001f)
        assertEquals(10f, resampled.points[1].x, 0.001f)
        assertEquals(20f, resampled.points[2].x, 0.001f)
    }
}
