package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuggestionReelMathTest {
    @Test
    fun `dragging up advances through alternatives and clamps at the ends`() {
        assertEquals(2, displayedReelIndex(selectedIndex = 1, dragOffsetSteps = -1.2f, maxIndex = 2))
        assertEquals(0, displayedReelIndex(selectedIndex = 1, dragOffsetSteps = 4f, maxIndex = 2))
    }

    @Test
    fun `reel viewport shows up to three candidates so alternatives are visible at rest`() {
        assertEquals(1, reelVisibleSlotCount(candidateCount = 1))
        assertEquals(2, reelVisibleSlotCount(candidateCount = 2))
        assertEquals(3, reelVisibleSlotCount(candidateCount = 3))
        assertEquals(3, reelVisibleSlotCount(candidateCount = 5))
    }

    @Test
    fun `reel viewport never shrinks below one slot even with no candidates`() {
        assertEquals(1, reelVisibleSlotCount(candidateCount = 0))
    }

    @Test
    fun `selected candidate centers within a multi-slot viewport`() {
        assertEquals(0f, reelCenterSlotOffset(visibleSlotCount = 1))
        assertEquals(0.5f, reelCenterSlotOffset(visibleSlotCount = 2))
        assertEquals(1f, reelCenterSlotOffset(visibleSlotCount = 3))
    }

    @Test
    fun `settle target is the signed distance to the selected alternative`() {
        assertEquals(-1f, reelSettleOffset(displayedIndex = 2, selectedIndex = 1))
        assertEquals(0f, reelSettleOffset(displayedIndex = 1, selectedIndex = 1))
    }

    @Test
    fun `replacement group width preserves the larger source or replacement span`() {
        assertEquals(2, replacementReelWidthSlots(sourceWordCount = 2, replacementWordCount = 1))
        assertEquals(3, replacementReelWidthSlots(sourceWordCount = 1, replacementWordCount = 3))
    }
}
