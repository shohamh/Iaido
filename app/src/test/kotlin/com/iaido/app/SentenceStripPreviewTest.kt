package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.recognition.ReplacementOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SentenceStripPreviewTest {
    @Test
    fun `alternative preview replaces only its source word and preserves punctuation and spacing`() {
        val state = state()

        val preview = SentenceStripPreviewMath.alternative(state, wordIndex = 1, side = SentenceAlternativeSide.ABOVE)

        assertEquals("we set it, now", preview?.sentenceText)
        assertEquals("set", (preview as? SentenceWordPreview)?.replacement)
        assertEquals(SentenceAlternativeSide.ABOVE, (preview as? SentenceWordPreview)?.side)
    }

    @Test
    fun `missing alternative does not create a preview`() {
        assertNull(SentenceStripPreviewMath.alternative(state(), wordIndex = 2, side = SentenceAlternativeSide.BELOW))
    }

    @Test
    fun `committed choice keeps the displaced word on the side that was selected`() {
        val base = state()
        val overridden = SentenceWordAlternativeOverride(
            selectedText = "set",
            displacedText = "put",
            side = SentenceAlternativeSide.BELOW,
        ).apply(base.words[1].copy(text = "set"))
        val stateAfterCommit = base.copy(words = base.words.toMutableList().also { it[1] = overridden })

        val nextPreview = SentenceStripPreviewMath.alternative(
            stateAfterCommit,
            wordIndex = 1,
            side = SentenceAlternativeSide.BELOW,
        )

        assertEquals("put", (nextPreview as? SentenceWordPreview)?.replacement)
        assertEquals("we put it, now", nextPreview?.sentenceText)
    }

    @Test
    fun `join preview can be selected from either source word`() {
        val state = joinedState()
        val option = state.replacementOptions.single()

        val fromLeft = SentenceStripPreviewMath.replacement(state, option, triggerWordIndex = 1)
        val fromRight = SentenceStripPreviewMath.replacement(state, option, triggerWordIndex = 2)

        assertEquals("we into it", fromLeft?.sentenceText)
        assertEquals("into", fromRight?.replacementText)
        assertEquals(listOf(1, 2), fromRight?.sourceWordIndices)
    }

    @Test
    fun `split preview renders both output words in the resulting sentence`() {
        val option = ReplacementOption(listOf("alot"), listOf("a", "lot"), 1.0)
        val state = SentenceStripState(
            sentenceText = "we alot now",
            sentenceStart = 0,
            selectionStart = 6,
            selectionEnd = 6,
            words = listOf(
                SentenceStripWord("we", "we", 0, 2, null, null),
                SentenceStripWord("alot", "alot", 3, 7, "a lot", null),
                SentenceStripWord("now", "now", 8, 11, null, null),
            ),
            replacementOptions = listOf(
                SentenceStripReplacement(option, 3, 7, listOf("alot")),
            ),
            language = Language.ENGLISH,
        )

        val preview = SentenceStripPreviewMath.alternative(state, 1, SentenceAlternativeSide.ABOVE)

        assertEquals("we a lot now", preview?.sentenceText)
        assertEquals(true, (preview as? SentenceReplacementPreview)?.isSplit)
    }

    @Test
    fun `deletion preview removes one adjacent space and preserves punctuation`() {
        val preview = SentenceStripPreviewMath.deletion(state(), 1..1)

        assertEquals("we it, now", preview?.sentenceText)
        assertEquals(3, preview?.sourceStart)
        assertEquals(7, preview?.sourceEndExclusive)
        assertEquals("put ", preview?.deletedText)
        assertEquals(listOf("1"), preview?.sourceWordIds)
    }

    private fun state() = SentenceStripState(
        sentenceText = "we put it, now",
        sentenceStart = 0,
        selectionStart = 5,
        selectionEnd = 5,
        words = listOf(
            SentenceStripWord("0", "we", 0, 2, above = "well", below = null),
            SentenceStripWord("1", "put", 3, 6, above = "set", below = "out"),
            SentenceStripWord("2", "it", 7, 9, above = "into", below = null),
            SentenceStripWord("3", "now", 11, 14, above = null, below = null),
        ),
        replacementOptions = emptyList(),
        language = Language.ENGLISH,
    )

    private fun joinedState() = SentenceStripState(
        sentenceText = "we in to it",
        sentenceStart = 0,
        selectionStart = 5,
        selectionEnd = 5,
        words = listOf(
            SentenceStripWord("0", "we", 0, 2, above = null, below = null),
            SentenceStripWord("1", "in", 3, 5, above = null, below = null),
            SentenceStripWord("2", "to", 6, 8, above = null, below = null),
            SentenceStripWord("3", "it", 9, 11, above = null, below = null),
        ),
        replacementOptions = listOf(
            SentenceStripReplacement(
                option = ReplacementOption(listOf("in", "to"), listOf("into"), 1.0),
                sourceStart = 3,
                sourceEndExclusive = 8,
                sourceWordIds = listOf("1", "2"),
            ),
        ),
        language = Language.ENGLISH,
    )
}
