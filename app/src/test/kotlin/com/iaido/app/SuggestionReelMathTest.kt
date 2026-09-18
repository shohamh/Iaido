package com.iaido.app

import com.iaido.core.recognition.SuggestionChip
import com.iaido.core.recognition.ReplacementOption
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

    @Test
    fun `reserved chip width adds padding around the measured word and has a usable minimum`() {
        assertEquals(56f, chipReservedWidthDp(measuredTextWidthDp = 10f))
        assertEquals(120f, chipReservedWidthDp(measuredTextWidthDp = 100f))
    }

    @Test
    fun `reserved chip width caps very long words so one chip cannot eat the whole strip`() {
        assertEquals(140f, chipReservedWidthDp(measuredTextWidthDp = 500f))
    }

    @Test
    fun `a row no wider than its reserved slot draws at the reserved width`() {
        assertEquals(80f, overflowDrawWidthDp(naturalWidthDp = 50f, reservedWidthDp = 80f, neighborReservedWidthDp = 100f))
    }

    @Test
    fun `a wider row grows up to its reserved width plus the neighbor it overlaps`() {
        assertEquals(
            150f,
            overflowDrawWidthDp(naturalWidthDp = 150f, reservedWidthDp = 80f, neighborReservedWidthDp = 100f),
        )
    }

    @Test
    fun `growth is capped at the reserved width plus neighbor plus item spacing`() {
        assertEquals(
            188f,
            overflowDrawWidthDp(naturalWidthDp = 300f, reservedWidthDp = 80f, neighborReservedWidthDp = 100f),
        )
    }

    @Test
    fun `a row cannot grow past its own reserved width when there is no neighbor to overlap`() {
        assertEquals(
            80f,
            overflowDrawWidthDp(naturalWidthDp = 300f, reservedWidthDp = 80f, neighborReservedWidthDp = null),
        )
    }

    @Test
    fun `reel centers the committed full word when candidates contain stale fragments`() {
        val chip = SuggestionChip(
            word = "Hello",
            alternatives = listOf("H", "he"),
            selectedIndex = 0,
        )

        val candidates = reelCandidatesForDisplay(chip)
        val selectedIndex = reelSelectedIndexForDisplay(chip, candidates)

        assertEquals(listOf("H", "he", "Hello"), candidates)
        assertEquals("Hello", candidates[selectedIndex])
    }

    @Test
    fun `inline replacement reels expose word options without one-letter fragments`() {
        val reels = inlineReplacementReels(
            listOf(
                ReplacementOption(listOf("Hello"), listOf("Hello"), 1.0),
                ReplacementOption(listOf("Hello"), listOf("Help"), 0.9),
                ReplacementOption(listOf("Hello"), listOf("H"), 0.8),
            ),
        )

        assertEquals(1, reels.size)
        assertEquals("Hello", reels.single().chip.word)
        assertEquals(listOf("Hello", "Help"), reels.single().chip.alternatives)
        assertEquals(listOf("Hello", "Help"), reels.single().options.map { it.replacementWords.single() })
        assertEquals(3, inlineReplacementOptionIds(
            listOf(
                ReplacementOption(listOf("Hello"), listOf("Hello"), 1.0),
                ReplacementOption(listOf("Hello"), listOf("Help"), 0.9),
                ReplacementOption(listOf("Hello"), listOf("H"), 0.8),
            ),
        ).size)
    }

    @Test
    fun `inline replacement reels create one block per active word`() {
        val reels = inlineReplacementReels(
            listOf(
                ReplacementOption(listOf("hello", "world"), listOf("hello", "world"), 1.0),
                ReplacementOption(listOf("hello", "world"), listOf("hi", "world"), 0.9),
                ReplacementOption(listOf("hello", "world"), listOf("hello", "earth"), 0.8),
            ),
        )

        assertEquals(listOf("hello", "world"), reels.map { it.chip.word })
        assertEquals(listOf("hello", "hi"), reels[0].chip.alternatives)
        assertEquals(listOf("world", "earth"), reels[1].chip.alternatives)
    }

    @Test
    fun `display candidate lookup uses the same de-duplicated order as the reel`() {
        val chip = SuggestionChip(word = "Hello", alternatives = listOf("Help"))

        assertEquals("Help", displayCandidateForIndex(chip, 0))
        assertEquals("Hello", displayCandidateForIndex(chip, 1))
    }

    @Test
    fun `auto-scroll targets the last chip for left-to-right and the first for right-to-left`() {
        assertEquals(4, autoScrollTargetIndex(chipCount = 5, rtl = false))
        assertEquals(0, autoScrollTargetIndex(chipCount = 5, rtl = true))
    }

    @Test
    fun `auto-scroll target is zero with no chips`() {
        assertEquals(0, autoScrollTargetIndex(chipCount = 0, rtl = false))
        assertEquals(0, autoScrollTargetIndex(chipCount = 0, rtl = true))
    }
}
