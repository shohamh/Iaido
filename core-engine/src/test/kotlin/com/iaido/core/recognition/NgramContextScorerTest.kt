package com.iaido.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NgramContextScorerTest {
    @Test
    fun `scorer normalizes sentence capitalization before looking up corpus evidence`() {
        val scorer = NgramContextScorer(
            bigrams = mapOf(("today" to "of") to 2.5),
            trigrams = mapOf(Triple("see", "today", "of") to 4.0),
        )

        assertEquals(scorer.score(listOf("see", "today"), "of"), scorer.score(listOf("See", "Today"), "Of"))
    }

    @Test
    fun `score in context includes bounded right-side trigram evidence`() {
        val scorer = NgramContextScorer(
            windowSize = 3,
            trigrams = mapOf(Triple("im", "going", "home") to 6.0),
        )

        val likely = scorer.scoreInContext(emptyList(), "im", listOf("going", "home", "soon"))
        val unlikely = scorer.scoreInContext(emptyList(), "am", listOf("going", "home", "soon"))

        assertTrue(likely > unlikely)
        assertEquals(6.0, likely)
    }

    @Test
    fun `personal pair boost contributes a bounded contextual preference`() {
        val scorer = NgramContextScorer(
            personalBigramBoost = { previous, next -> if (previous == "i" && next == "like") 6.0 else 1.0 },
        )

        val preferred = scorer.score(listOf("i"), "like")
        val unseen = scorer.score(listOf("i"), "love")

        assertTrue(preferred > unseen)
        assertTrue(preferred < 1.0)
    }
}
