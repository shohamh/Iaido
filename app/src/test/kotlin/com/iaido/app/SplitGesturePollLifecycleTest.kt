package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SplitGesturePollLifecycleTest {
    @Test
    fun `new gesture invalidates a pending split poll before its pointers begin`() {
        val lifecycle = SplitGesturePollLifecycle()

        val previousGeneration = lifecycle.startGesture()
        assertEquals(previousGeneration, lifecycle.scheduleCurrentGeneration())

        val currentGeneration = lifecycle.startGesture()

        assertFalse(lifecycle.isCurrent(previousGeneration))
        assertNull(lifecycle.scheduledGeneration)
        assertEquals(currentGeneration, lifecycle.scheduleCurrentGeneration())
    }

    @Test
    fun `additional pointers preserve their gesture generation`() {
        val lifecycle = SplitGesturePollLifecycle()

        val generation = lifecycle.startGesture()

        assertTrue(lifecycle.isCurrent(generation))
        assertEquals(generation, lifecycle.scheduleCurrentGeneration())
        assertNull(lifecycle.scheduleCurrentGeneration())
    }
}
