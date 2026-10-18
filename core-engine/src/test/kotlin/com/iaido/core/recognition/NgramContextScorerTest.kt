package com.iaido.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
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
}
