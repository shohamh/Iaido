package com.iaido.app

import com.iaido.core.recognition.SuggestionChip
import com.iaido.core.recognition.ReplacementOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionReelMathTest {
    @Test
    fun `dragging up advances through alternatives and clamps at the ends`() {
        assertEquals(2, displayedReelIndex(selectedIndex = 1, dragOffsetSteps = -1.2f, maxIndex = 2))
        assertEquals(0, displayedReelIndex(selectedIndex = 1, dragOffsetSteps = 4f, maxIndex = 2))
    }

    @Test
    fun `stale resting offset is ignored while a reel is not actively settling`() {
        assertEquals(
            0f,
            reelRenderOffset(
                offset = -4f,
                minOffset = -1f,
                maxOffset = 0f,
                isDragging = false,
                isSettling = false,
            ),
        )
        assertEquals(
            -1f,
            reelRenderOffset(
                offset = -4f,
                minOffset = -1f,
                maxOffset = 0f,
                isDragging = false,
                isSettling = true,
            ),
        )
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
    fun `reserved chip width keeps enough room for a long word`() {
        assertEquals(520f, chipReservedWidthDp(measuredTextWidthDp = 500f))
    }

    @Test
    fun `reserved chip width follows the widest candidate`() {
        assertEquals(
            120f,
            widestChipReservedWidthDp(measuredTextWidthsDp = listOf(10f, 100f, 50f)),
        )
    }

    @Test
    fun `reserved replacement word width hugs a single letter instead of padding it into a wide box`() {
        assertEquals(44f, replacementWordReservedWidthDp(measuredTextWidthDp = 6f))
    }

    @Test
    fun `reserved replacement word width adds tight padding around a short word`() {
        assertEquals(60f, replacementWordReservedWidthDp(measuredTextWidthDp = 50f))
    }

    @Test
    fun `reserved replacement word width keeps enough room for a long word`() {
        assertEquals(510f, replacementWordReservedWidthDp(measuredTextWidthDp = 500f))
    }

    @Test
    fun `reserved replacement word width clamps to its minimum and maximum`() {
        assertEquals(44f, replacementWordReservedWidthDp(measuredTextWidthDp = 0f))
        assertEquals(44f, replacementWordReservedWidthDp(measuredTextWidthDp = -20f))
        assertEquals(140f, replacementWordReservedWidthDp(measuredTextWidthDp = 130f))
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
    fun `reel keeps daytime visible alongside its real alternative`() {
        val chip = SuggestionChip(
            word = "daytime",
            alternatives = listOf("daylight"),
            selectedIndex = 0,
        )

        val candidates = reelCandidatesForDisplay(chip)

        assertEquals(listOf("daylight", "daytime"), candidates)
        assertEquals(1, reelSelectedIndexForDisplay(chip, candidates))
    }

    @Test
    fun `reel state identity changes when a current word gains another real candidate`() {
        val chip = SuggestionChip(
            word = "daytime",
            alternatives = listOf("daylight"),
            selectedIndex = 0,
            id = 7,
        )

        val originalCandidates = reelCandidatesForDisplay(chip)
        val refreshedCandidates = originalCandidates + "daybreak"

        assertNotEquals(
            reelStateKey(chip, originalCandidates),
            reelStateKey(chip, refreshedCandidates),
        )
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
    fun `single-word split candidates attach to the source reel instead of the grouped reel`() {
        val options = listOf(
            ReplacementOption(listOf("help"), listOf("help"), 1.0),
            ReplacementOption(listOf("help"), listOf("he", "lp"), 0.9),
        )
        val chips = listOf(SuggestionChip(word = "help", alternatives = listOf("help"), id = 4))

        val inlineIds = inlineReplacementOptionIds(options)
        val attachments = attachReplacementCandidates(chips, options.filterNot { it.id in inlineIds })
        val edgeOptions = edgeReplacementOptions(
            options.filterNot { it.id in inlineIds },
            liveReplacementOptionIds = emptySet(),
            attachedOptionIds = attachments.map { it.option.id }.toSet(),
        )

        assertTrue(edgeOptions.isEmpty())
        assertEquals(listOf(options[1]), attachments.map { it.option })
        assertEquals(listOf("help"), inlineReplacementReels(options).map { it.chip.word })
    }

    @Test
    fun `candidate score overlay follows the visible replacement word`() {
        val options = listOf(
            ReplacementOption(listOf("help"), listOf("help"), 0.91),
            ReplacementOption(listOf("help"), listOf("held"), 0.73),
        )

        assertEquals(0.73, replacementScoreForWord(options, wordIndex = 0, word = "held"))
        assertEquals("0.910", candidateScoreLabel(0.91))
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
