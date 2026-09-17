package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.recognition.SessionWord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryJoinCandidatesTest {
    private fun word(id: Int, current: String, start: Int = 0, end: Int = current.length) = SessionWord(
        id = id,
        start = start,
        end = end,
        original = current,
        current = current,
        candidates = listOf(current),
        corrected = false,
    )

    @Test
    fun `an adjacent pair that forms a dictionary word offers exactly one candidate`() {
        val words = listOf(word(0, "wh"), word(1, "at"))
        val dictionary = listOf(WordEntry("what", 0.5))

        val candidates = historyJoinCandidates(words, dictionary)

        assertEquals(1, candidates.size)
        assertEquals(listOf("wh", "at"), candidates.first().sourceWords)
        assertEquals(listOf("what"), candidates.first().replacementWords)
        assertEquals(0.5, candidates.first().score)
    }

    @Test
    fun `a pair that does not form a dictionary word offers nothing`() {
        val words = listOf(word(0, "hello"), word(1, "world"))
        val dictionary = listOf(WordEntry("hello", 0.1), WordEntry("world", 0.1), WordEntry("something", 0.1))

        assertTrue(historyJoinCandidates(words, dictionary).isEmpty())
    }

    @Test
    fun `capitalization of the first word is preserved in the replacement`() {
        val words = listOf(word(0, "Wh"), word(1, "at"))
        val dictionary = listOf(WordEntry("what", 0.5))

        val candidates = historyJoinCandidates(words, dictionary)

        assertEquals(listOf("What"), candidates.first().replacementWords)
    }

    @Test
    fun `fewer than two words offers nothing`() {
        val dictionary = listOf(WordEntry("what", 0.5))

        assertTrue(historyJoinCandidates(emptyList(), dictionary).isEmpty())
        assertTrue(historyJoinCandidates(listOf(word(0, "wh")), dictionary).isEmpty())
    }
}
