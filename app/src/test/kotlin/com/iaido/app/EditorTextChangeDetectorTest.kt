package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EditorTextChangeDetectorTest {
    @Test
    fun `external replacement returns changed token and range`() {
        val detector = EditorTextChangeDetector()
        detector.reset(EditorSnapshot("I typed teh", selectionStart = 11, selectionEnd = 11))

        val candidate = detector.observe(EditorSnapshot("I typed the", selectionStart = 11, selectionEnd = 11))

        assertEquals(
            ManualEditCandidate(start = 8, end = 11, original = "teh", replacement = "the"),
            candidate,
        )
    }

    @Test
    fun `expected own edit is consumed without producing a candidate`() {
        val detector = EditorTextChangeDetector()
        detector.reset(EditorSnapshot("hello", selectionStart = 5, selectionEnd = 5))
        detector.expectOwnEdit(start = 5, end = 5, replacement = " world")

        assertNull(detector.observe(EditorSnapshot("hello world", selectionStart = 11, selectionEnd = 11)))
    }

    @Test
    fun `own edit whose replacement shares a prefix with the replaced text is still consumed without producing a candidate`() {
        val detector = EditorTextChangeDetector()
        detector.reset(EditorSnapshot("X in", selectionStart = 4, selectionEnd = 4))
        detector.expectOwnEdit(start = 2, end = 4, replacement = "in to")

        assertNull(detector.observe(EditorSnapshot("X in to", selectionStart = 7, selectionEnd = 7)))
    }

    @Test
    fun `own edit delivered through an intermediate snapshot is consumed without a candidate`() {
        val detector = EditorTextChangeDetector()
        detector.reset(EditorSnapshot("hello", selectionStart = 5, selectionEnd = 5))
        detector.expectOwnEdit(start = 5, end = 5, replacement = " world")

        assertNull(detector.observe(EditorSnapshot("hello w", selectionStart = 7, selectionEnd = 7)))
        assertNull(detector.observe(EditorSnapshot("hello world", selectionStart = 11, selectionEnd = 11)))
    }

    @Test
    fun `a genuine external edit after a same-length own edit is still detected`() {
        val detector = EditorTextChangeDetector()
        detector.reset(EditorSnapshot("X in", selectionStart = 4, selectionEnd = 4))
        detector.expectOwnEdit(start = 2, end = 4, replacement = "in to")

        val candidate = detector.observe(EditorSnapshot("X to", selectionStart = 4, selectionEnd = 4))

        assertNotNull(candidate)
    }
}
