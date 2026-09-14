package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
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
}
