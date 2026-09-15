package com.iaido.app

import com.iaido.core.recognition.ReplacementOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplacementReelLayoutTest {
    @Test
    fun `one source word and one replacement word render as an ordinary single reel`() {
        val layout = replacementReelLayout(
            ReplacementOption(listOf("teh"), listOf("the"), 0.8),
            rtl = false,
        )

        assertEquals(1, layout.sourceSlotCount)
        assertEquals(1, layout.reelCount)
        assertEquals(1, layout.widthSlots)
        assertFalse(layout.isJoined)
        assertEquals(listOf("the"), layout.renderedWords)
    }

    @Test
    fun `two source words joined into one render as one wide reel`() {
        val layout = replacementReelLayout(
            ReplacementOption(listOf("in", "the"), listOf("inthe"), 0.8),
            rtl = false,
        )

        assertEquals(2, layout.sourceSlotCount)
        assertEquals(1, layout.reelCount)
        assertEquals(2, layout.widthSlots)
        assertTrue(layout.isJoined)
        assertEquals(listOf("inthe"), layout.renderedWords)
    }

    @Test
    fun `one source word split into two or three renders coordinated reels`() {
        val two = replacementReelLayout(
            ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.8),
            rtl = false,
        )
        val three = replacementReelLayout(
            ReplacementOption(listOf("alot"), listOf("a", "lot", "more"), 0.8),
            rtl = false,
        )

        assertEquals(2, two.reelCount)
        assertEquals(2, two.widthSlots)
        assertEquals(listOf("in", "the"), two.renderedWords)
        assertEquals(3, three.reelCount)
        assertEquals(3, three.widthSlots)
        assertEquals(listOf("a", "lot", "more"), three.renderedWords)
    }

    @Test
    fun `rtl lays out coordinated replacement reels in visual reverse order`() {
        val layout = replacementReelLayout(
            ReplacementOption(listOf("one"), listOf("first", "second", "third"), 0.8),
            rtl = true,
        )

        assertEquals(listOf("third", "second", "first"), layout.renderedWords)
    }

    @Test
    fun `drag preview commits only on release and cancellation makes no replacement`() {
        val original = ReplacementOption(listOf("inthe"), listOf("inthe"), 1.0)
        val split = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.8)
        val selection = ReplacementReelSelection(listOf(original, split))

        assertEquals(split, selection.preview(1))
        assertNull(selection.committed)
        selection.cancel()
        assertNull(selection.committed)
        assertFalse(selection.isPreviewing)
        assertEquals(split, selection.release(1))
        assertEquals(split, selection.committed)
    }

    @Test
    fun `refreshed reel keeps the option selected by stable id instead of resetting to first`() {
        val selected = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.8)
        val refreshedOriginal = ReplacementOption(listOf("inthe"), listOf("inthe"), 1.1)
        val refreshedSelected = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.9)

        val selectedIndex = replacementSelectedIndex(
            options = listOf(refreshedOriginal, refreshedSelected),
            selectedOptionId = selected.id,
        )

        assertEquals(1, selectedIndex)
        assertEquals(refreshedSelected, listOf(refreshedOriginal, refreshedSelected)[selectedIndex])
    }

    @Test
    fun `preview selection survives coordinator candidate refresh before release`() {
        val original = ReplacementOption(listOf("inthe"), listOf("inthe"), 1.0)
        val split = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.8)
        val refreshedOriginal = ReplacementOption(listOf("inthe"), listOf("inthe"), 1.1)
        val refreshedSplit = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.9)
        val selection = ReplacementReelSelection(listOf(original, split))

        selection.preview(split)
        selection.updateOptions(listOf(refreshedOriginal, refreshedSplit))

        assertEquals(1, selection.selectedIndex())
        assertEquals(refreshedSplit, selection.selectedOption())
    }

    @Test
    fun `accessibility description exposes cardinality words and candidate position`() {
        val description = replacementReelDescription(
            option = ReplacementOption(listOf("in", "the"), listOf("inthe"), 0.8),
            candidateIndex = 1,
            candidateCount = 3,
        )

        assertEquals(
            "Iaido replacement: 2 source words to 1 replacement word; current words in the; candidate 2 of 3",
            description,
        )
    }

    @Test
    fun `replacement cursor advances to the end of the exact replacement span`() {
        assertEquals(12, replacementCursorAfterCommit(spanStart = 6, replacementWords = listOf("in", "the")))
        assertEquals(11, replacementCursorAfterCommit(spanStart = 6, replacementWords = listOf("inthe")))
    }
}
