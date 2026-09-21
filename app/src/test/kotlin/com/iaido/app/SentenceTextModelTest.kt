package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SessionWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceTextModelTest {
    @Test
    fun keepsCursorAndWordSpansAtEndInsideAndInGap() {
        val text = "we put now."
        val atEnd = model(text, cursor = 2)
        val inside = model(text, cursor = 5)
        val inGap = model(text, cursor = 3)

        assertEquals(2, atEnd.selectionStart)
        assertEquals(5, inside.selectionStart)
        assertEquals(3, inGap.selectionStart)
        assertEquals(listOf("we", "put", "now"), atEnd.words.map(SentenceStripWord::text))
        assertEquals(listOf(0 to 2, 3 to 6, 7 to 10), atEnd.words.map { it.start to it.endExclusive })
    }

    @Test
    fun preservesPunctuationAndSeparatorsWithoutMakingThemWords() {
        val state = model("well, done!", cursor = 5)

        assertEquals("well, done!", state.sentenceText)
        assertEquals(listOf("well", "done"), state.words.map(SentenceStripWord::text))
        assertEquals("well, ", state.sentenceText.substring(0, state.words[1].start))
    }

    @Test
    fun usesAbsoluteUtf16OffsetsForExtractedTextWithNonzeroStart() {
        val state = model("active word.", cursor = 7, offset = 41)

        assertEquals(41, state.sentenceStart)
        assertEquals(48, state.selectionStart)
        assertEquals(41 to 47, state.words.first().start to state.words.first().endExclusive)
        assertEquals(48 to 52, state.words.last().start to state.words.last().endExclusive)
    }

    @Test
    fun retainsHebrewMarksAsPartOfTheirWordSpan() {
        val text = "שָׁלוֹם לך."
        val state = model(text, cursor = text.length, language = Language.HEBREW)

        assertEquals(listOf("שָׁלוֹם", "לך"), state.words.map(SentenceStripWord::text))
        assertEquals(text.indexOf("לך") to text.indexOf("לך") + 2, state.words.last().start to state.words.last().endExclusive)
    }

    @Test
    fun keepsIdsForUnaffectedWordsWhenEarlierTextIsInserted() {
        val before = model("one two", cursor = 7)
        val oneId = before.words[0].id
        val twoId = before.words[1].id
        val after = model("x one two", cursor = 9, previous = before)

        assertEquals(listOf("x", "one", "two"), after.words.map(SentenceStripWord::text))
        assertEquals(oneId, after.words[1].id)
        assertEquals(twoId, after.words[2].id)
        assertNotEquals(oneId, after.words[0].id)
    }

    @Test
    fun givesSplitAndJoinOutputSpansFreshIds() {
        val single = model("alot", cursor = 4)
        val split = model("a lot", cursor = 5, previous = single)
        val pair = model("in to", cursor = 5)
        val joined = model("into", cursor = 4, previous = pair)

        assertEquals(2, split.words.size)
        assertTrue(split.words.none { it.id in single.words.map(SentenceStripWord::id) })
        assertEquals(1, joined.words.size)
        assertTrue(joined.words.none { output -> pair.words.any { it.id == output.id } })
    }

    @Test
    fun resolvesOnlyCurrentSentenceAndKeepsJoinSourceSpan() {
        val join = ReplacementOption(listOf("in", "to"), listOf("into"), score = 1.0)
        val state = model("Before! in to now.", cursor = 12, options = listOf(join))

        assertEquals("in to now.", state.sentenceText)
        assertEquals(1, state.replacementOptions.size)
        assertEquals(state.sentenceStart + 0, state.replacementOptions.single().sourceStart)
        assertEquals(state.sentenceStart + 5, state.replacementOptions.single().sourceEndExclusive)
    }

    @Test
    fun removesCaseOnlyAndDuplicateAlternativesUsingLanguageLocale() {
        val history = listOf(
            SessionWord(
                id = 7,
                start = 0,
                end = 2,
                original = "we",
                current = "we",
                candidates = listOf("we", "We", "wE", "wee", "WEE", "wee"),
                corrected = false,
            ),
        )
        val state = model("we", cursor = 2, history = history)

        assertEquals("wee", state.words.single().above)
        assertEquals(null, state.words.single().below)
    }

    @Test
    fun exposesNoStaleWordsWithoutObservationOrForSensitiveEditors() {
        val before = model("private sentence", cursor = 6)
        val missing = model("", cursor = 0, previous = before)
        val password = model("secret text", cursor = 6, previous = before, observationAllowed = false)

        assertTrue(missing.words.isEmpty())
        assertTrue(password.words.isEmpty())
        assertEquals("", password.sentenceText)
        assertFalse(password.replacementOptions.isNotEmpty())
    }

    private fun model(
        text: String,
        cursor: Int,
        offset: Int = 0,
        language: Language = Language.ENGLISH,
        history: List<SessionWord> = emptyList(),
        options: List<ReplacementOption> = emptyList(),
        previous: SentenceStripState? = null,
        observationAllowed: Boolean = true,
    ): SentenceStripState = SentenceTextModel.update(
        previous = previous,
        snapshot = EditorSnapshot(
            text = text,
            selectionStart = offset + cursor,
            selectionEnd = offset + cursor,
            offset = offset,
        ),
        language = language,
        history = history,
        replacementOptions = options,
        textObservationAllowed = observationAllowed,
    )
}
