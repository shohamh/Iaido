package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NgramAndSplitTest {
    @Test
    fun `context scorer combines recent bigram and trigram evidence`() {
        val scorer = NgramContextScorer(
            bigrams = mapOf(("to" to "there") to 2.0),
            trigrams = mapOf(Triple("go", "to", "there") to 3.0),
        )
        assertEquals(5.0, scorer.score(listOf("go", "to"), "there"))
    }

    @Test
    fun `split candidates concatenate in touch down order within grace window`() {
        val merger = SplitWordMerger()
        val result = merger.merge(listOf("th"), listOf("ere"), listOf(WordEntry("there", 1.0)), 100, 350)
        assertEquals(listOf("there"), result.map { it.word })
    }

    @Test
    fun `split candidates expire after grace window`() {
        val result = SplitWordMerger().merge(listOf("th"), listOf("ere"), listOf(WordEntry("there", 1.0)), 100, 451)
        assertEquals(emptyList<WordEntry>(), result)
    }
}
