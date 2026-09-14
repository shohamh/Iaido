package com.ninjakeys.app

import android.graphics.Rect
import android.view.MotionEvent
import com.ninjakeys.core.gesture.GesturePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerInjectorTest {
    @Test
    fun swipeHasDownMovesAndUpWithMonotonicTimes() {
        val events = PointerInjector.buildSwipeEvents(
            points = listOf(
                GesturePoint(10f, 20f, 0L),
                GesturePoint(20f, 30f, 10L),
            ),
            surfaceBounds = Rect(100, 200, 500, 600),
            startTimeMs = 1_000L,
            stepMs = 5L,
        )

        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), events.map { it.action })
        assertEquals(listOf(1_000L, 1_005L, 1_010L), events.map { it.eventTimeMs })
        assertTrue(events.zipWithNext().all { (a, b) -> b.eventTimeMs >= a.eventTimeMs })
        assertEquals(110f, events.first().pointers.single().x, 0.001f)
        assertEquals(220f, events.first().pointers.single().y, 0.001f)
    }

    @Test
    fun cancelEndsWithoutUp() {
        val events = PointerInjector.buildSwipeEvents(
            points = listOf(GesturePoint(10f, 20f, 0L), GesturePoint(20f, 30f, 10L)),
            surfaceBounds = Rect(0, 0, 100, 100),
            startTimeMs = 100L,
            stepMs = 10L,
            cancel = true,
        )

        assertEquals(MotionEvent.ACTION_CANCEL, events.last().action)
        assertTrue(events.none { it.action == MotionEvent.ACTION_UP })
    }

    @Test
    fun multiPointerSequenceUsesStableIdsAndActionIndices() {
        val events = PointerInjector.buildMultiPointerEvents(
            paths = listOf(
                listOf(GesturePoint(10f, 20f, 0L), GesturePoint(20f, 30f, 10L)),
                listOf(GesturePoint(40f, 50f, 0L), GesturePoint(50f, 60f, 10L)),
            ),
            surfaceBounds = Rect(100, 200, 500, 600),
            startTimeMs = 1_000L,
            stepMs = 5L,
        )

        assertEquals(MotionEvent.ACTION_DOWN, events[0].action)
        assertEquals(listOf(0), events[0].pointers.map { it.id })
        assertEquals(MotionEvent.ACTION_POINTER_DOWN, events[1].action)
        assertEquals(1, events[1].actionIndex)
        assertEquals(listOf(0, 1), events[1].pointers.map { it.id })
        assertEquals(MotionEvent.ACTION_POINTER_UP, events[events.lastIndex - 1].action)
        assertEquals(1, events[events.lastIndex - 1].actionIndex)
        assertEquals(MotionEvent.ACTION_UP, events.last().action)
    }

    @Test
    fun seededJitterIsRepeatableButDifferentSeedsMovePoints() {
        val points = listOf(GesturePoint(10f, 20f, 0L), GesturePoint(20f, 30f, 10L))
        val bounds = Rect(0, 0, 100, 100)
        val first = PointerInjector.buildSwipeEvents(points, bounds, 100L, 10L, jitterSeed = 7L, jitterPx = 2f)
        val same = PointerInjector.buildSwipeEvents(points, bounds, 100L, 10L, jitterSeed = 7L, jitterPx = 2f)
        val different = PointerInjector.buildSwipeEvents(points, bounds, 100L, 10L, jitterSeed = 8L, jitterPx = 2f)

        assertEquals(first, same)
        assertNotEquals(first, different)
    }
}
