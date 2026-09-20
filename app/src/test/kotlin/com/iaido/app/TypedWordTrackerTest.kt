package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypedWordTrackerTest {
    @Test
    fun `letters open a token that closes at the delimiter`() {
        val tracker = TypedWordTracker()

        assertNull(tracker.onCommitted("h", cursorBefore = 3, cursorAfter = 4))
        assertNull(tracker.onCommitted("i", cursorBefore = 4, cursorAfter = 5))
        assertTrue(tracker.isTracking)

        assertEquals(
            TypedWordSpan(start = 3, end = 5, word = "hi"),
            tracker.onCommitted(" ", cursorBefore = 5, cursorAfter = 6),
        )
        assertFalse(tracker.isTracking)
    }

    @Test
    fun `a multi-character commit closes the preceding typed letter`() {
        val tracker = TypedWordTracker()
        tracker.onCommitted("h", cursorBefore = 0, cursorAfter = 1)

        // A swiped word is recorded separately by the service, but the tracker still closes the
        // typed token that preceded it.
        assertEquals(
            TypedWordSpan(0, 1, "h"),
            tracker.onCommitted("ello", cursorBefore = 1, cursorAfter = 5),
        )
        assertFalse(tracker.isTracking)
    }

    @Test
    fun `digits and punctuation are not treated as typed letters`() {
        val tracker = TypedWordTracker()
        tracker.onCommitted("t", cursorBefore = 0, cursorAfter = 1)
        tracker.onCommitted("e", cursorBefore = 1, cursorAfter = 2)

        assertEquals(TypedWordSpan(0, 2, "te"), tracker.onCommitted("5", cursorBefore = 2, cursorAfter = 3))
        assertFalse(tracker.isTracking)
    }

    @Test
    fun `deleting into the token shrinks it`() {
        val tracker = TypedWordTracker()
        tracker.onCommitted("t", cursorBefore = 0, cursorAfter = 1)
        tracker.onCommitted("e", cursorBefore = 1, cursorAfter = 2)
        tracker.onCommitted("h", cursorBefore = 2, cursorAfter = 3)

        tracker.onDeleted(count = 1, cursorBefore = 3)

        assertEquals(TypedWordSpan(0, 2, "te"), tracker.closeAt(cursorAtEnd = 2))
    }

    @Test
    fun `deleting the token's first letter discards it`() {
        val tracker = TypedWordTracker()
        tracker.onCommitted("t", cursorBefore = 0, cursorAfter = 1)
        tracker.onCommitted("e", cursorBefore = 1, cursorAfter = 2)

        tracker.onDeleted(count = 2, cursorBefore = 2)

        assertFalse(tracker.isTracking)
        assertNull(tracker.closeAt(cursorAtEnd = 0))
    }

    @Test
    fun `a token that no longer ends at the caret is dropped instead of recorded`() {
        val tracker = TypedWordTracker()
        tracker.onCommitted("h", cursorBefore = 0, cursorAfter = 1)
        tracker.onCommitted("i", cursorBefore = 1, cursorAfter = 2)

        // The editor moved under us (a host edit, a correction): the span may hold other text now.
        assertNull(tracker.closeAt(cursorAtEnd = 9))
        assertFalse(tracker.isTracking)
    }

    @Test
    fun `a single letter closes into a recordable word span`() {
        val tracker = TypedWordTracker()
        tracker.onCommitted("h", cursorBefore = 0, cursorAfter = 1)

        assertEquals(
            TypedWordSpan(start = 0, end = 1, word = "h"),
            tracker.onCommitted(" ", cursorBefore = 1, cursorAfter = 2),
        )
    }

    @Test
    fun `reset drops an open token`() {
        val tracker = TypedWordTracker()
        tracker.onCommitted("h", cursorBefore = 0, cursorAfter = 1)

        tracker.reset()

        assertFalse(tracker.isTracking)
        assertNull(tracker.openSpan())
    }
}
