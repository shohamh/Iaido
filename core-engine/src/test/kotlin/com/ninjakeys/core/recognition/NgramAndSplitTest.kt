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
    fun `context window can be limited to one preceding word`() {
        val scorer = NgramContextScorer(
            windowSize = 1,
            bigrams = mapOf(("to" to "there") to 2.0),
            trigrams = mapOf(Triple("go", "to", "there") to 3.0),
        )

        assertEquals(2.0, scorer.score(listOf("go", "to"), "there"))
    }

    @Test
    fun `context weights and three-word window use the latest words`() {
        val scorer = NgramContextScorer(
            windowSize = 3,
            bigramWeight = 0.5,
            trigramWeight = 2.0,
            bigrams = mapOf(("to" to "there") to 4.0),
            trigrams = mapOf(Triple("go", "to", "there") to 3.0),
        )

        assertEquals(8.0, scorer.score(listOf("ignore", "go", "to"), "there"))
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

    @Test
    fun `split merger supports more than two touch ordered parts`() {
        val result = SplitWordMerger().mergeParts(
            listOf("t", "he", "re"),
            listOf(WordEntry("there", 2.0), WordEntry("three", 1.0)),
        )

        assertEquals(listOf("there"), result.map { it.word })
    }
}
