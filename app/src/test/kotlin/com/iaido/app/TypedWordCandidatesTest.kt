package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val dictionary = listOf(
    WordEntry("the", 900.0),
    WordEntry("them", 500.0),
    WordEntry("there", 700.0),
    WordEntry("ten", 400.0),
    WordEntry("hi", 300.0),
    WordEntry("him", 250.0),
    WordEntry("his", 600.0),
    WordEntry("unrelated", 999.0),
)

class TypedWordCandidatesTest {
    @Test
    fun `prefix matches come first, most frequent first`() {
        assertEquals(
            listOf("there", "them"),
            typedWordCandidates("the", dictionary, limit = 3),
        )
        // "ten" shares neither the prefix nor a single edit with "the".
        assertFalse(typedWordCandidates("the", dictionary, limit = 3).contains("ten"))
    }

    @Test
    fun `a one-edit typo still finds its correction`() {
        // "thw" shares no prefix with "the", so this only works through the one-edit channel.
        assertEquals(listOf("the"), typedWordCandidates("thw", dictionary, limit = 2))
        // A transposition is two edits, so it is deliberately not offered.
        assertFalse(typedWordCandidates("teh", dictionary).contains("the"))
    }

    @Test
    fun `the typed word itself and unrelated words are never offered`() {
        val candidates = typedWordCandidates("hi", dictionary, limit = 5)

        assertTrue(candidates.containsAll(listOf("him", "his")))
        assertFalse(candidates.contains("hi"))
        assertFalse(candidates.contains("unrelated"))
    }

    @Test
    fun `single letters and empty dictionaries offer nothing`() {
        assertEquals(emptyList<String>(), typedWordCandidates("h", dictionary))
        assertEquals(emptyList<String>(), typedWordCandidates("hi", emptyList()))
        assertEquals(emptyList<String>(), typedWordCandidates("hi", dictionary, limit = 0))
    }

    @Test
    fun `one edit covers insertions, deletions, and substitutions`() {
        assertTrue(isOneEditAway("the", "the"))
        assertTrue(isOneEditAway("the", "them"))
        assertTrue(isOneEditAway("them", "the"))
        assertTrue(isOneEditAway("the", "tho"))
        assertFalse(isOneEditAway("the", "those"))
        assertFalse(isOneEditAway("the", "aaa"))
    }
}