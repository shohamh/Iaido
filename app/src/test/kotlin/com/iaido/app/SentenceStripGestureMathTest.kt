package com.iaido.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceStripGestureMathTest {
    @Test
    fun `small horizontal drift over a word remains eligible for held cursor scrubbing`() {
        assertFalse(SentenceStripGestureMath.hasStartedHorizontalScroll(12f, density = 1f, hasWordOrigin = true))
        assertFalse(SentenceStripGestureMath.hasStartedHorizontalScroll(24f, density = 2f, hasWordOrigin = true))
        assertTrue(SentenceStripGestureMath.hasStartedHorizontalScroll(8f, density = 1f, hasWordOrigin = false))
        assertTrue(SentenceStripGestureMath.hasStartedHorizontalScroll(21f, density = 1f, hasWordOrigin = true))
    }

    @Test
    fun `held word drag arms cursor scrubbing before it reaches the edge zone`() {
        assertFalse(SentenceStripGestureMath.shouldStartCursorScrub(159L, hasWordOrigin = true))
        assertTrue(SentenceStripGestureMath.shouldStartCursorScrub(160L, hasWordOrigin = true))
        assertFalse(SentenceStripGestureMath.shouldStartCursorScrub(500L, hasWordOrigin = false))
    }

    @Test
    fun `edge scroll promotes a held word drag to cursor scrubbing only inside the edge zone`() {
        assertFalse(SentenceStripGestureMath.shouldPromoteEdgeScrollToCursor(500L, true, false))
        assertFalse(SentenceStripGestureMath.shouldPromoteEdgeScrollToCursor(120L, true, true))
        assertFalse(SentenceStripGestureMath.shouldPromoteEdgeScrollToCursor(500L, false, true))
        assertTrue(SentenceStripGestureMath.shouldPromoteEdgeScrollToCursor(160L, true, true))
    }
}
