package com.ninjakeys.core.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MultiFingerGestureTest {
    @Test
    fun `horizontal movement is distinct from finger separation`() {
        val detector = MultiFingerGestureDetector(minimumMovement = 20f)

        assertEquals(GestureTrigger.NONE, detector.detect(0f, 0f, 10f, 10f, 2))
        assertEquals(GestureTrigger.HORIZONTAL, detector.detect(0f, 0f, 25f, 3f, 2))
    }

    @Test
    fun `vertical downward movement maps to dismiss`() {
        assertEquals(
            GestureTrigger.DOWN,
            MultiFingerGestureDetector(20f).detect(0f, 0f, 3f, 25f, 2),
        )
    }
}
