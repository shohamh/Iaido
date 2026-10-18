package com.iaido.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceCandidateRerankerTest {
    @Test
    fun `reranks only the configured neighbor window and reports affected ids`() {
        val words = (0 until 8).map { index ->
            SessionWord(index, index * 2, index * 2 + 1, "w$index", "w$index", listOf("w$index", "alt$index"), false)
        }
        val reranker = SentenceCandidateReranker(
            contextScorer = NgramContextScorer(bigrams = mapOf(("w3" to "alt4") to 10.0)),
            neighborWindow = 1,
        )

        val result = reranker.rerank(words, changedId = 4)

        assertEquals(setOf(3, 4, 5), result.affectedIds)
        assertEquals(words[2].candidates, result.words[2].candidates)
        assertEquals(listOf("alt4", "w4"), result.words[4].candidates)
        assertEquals(words[6].candidates, result.words[6].candidates)
    }

    @Test
    fun `current word wins an otherwise exact tie and remains a candidate`() {
        val entry = SessionWord(1, 0, 3, "hey", "hey", listOf("heyday"), false)
        val result = SentenceCandidateReranker(NgramContextScorer()).rerank(listOf(entry), 1)

        assertTrue(result.words.single().candidates.contains("hey"))
        assertEquals("hey", result.words.single().candidates.first())
    }

    @Test
    fun `right context can promote an alternative across the confidence margin`() {
        val words = listOf(
            SessionWord(1, 0, 4, "here", "here", listOf("here", "there"), false),
            SessionWord(2, 5, 9, "home", "home", listOf("home"), false),
        )
        val reranker = SentenceCandidateReranker(
            contextScorer = NgramContextScorer(bigrams = mapOf(("there" to "home") to 3.0)),
            neighborWindow = 1,
        )

        val result = reranker.rerank(words, changedId = 1)

        assertEquals(listOf("there", "here"), result.words[0].candidates)
    }

    @Test
    fun `weak alternatives stay behind current word`() {
        val words = listOf(
            SessionWord(1, 0, 4, "here", "here", listOf("here", "there"), false),
            SessionWord(2, 5, 9, "home", "home", listOf("home"), false),
        )
        val reranker = SentenceCandidateReranker(
            contextScorer = NgramContextScorer(bigrams = mapOf(("there" to "home") to 0.8)),
        )

        val result = reranker.rerank(words, changedId = 1)

        assertEquals("here", result.words[0].candidates.first())
    }
}
