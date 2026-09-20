package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiPathOrderHypothesisTest {
    @Test
    fun `enumerates observed order and every pair swap including the non adjacent pair`() {
        val hypotheses = MultiPathOrderHypothesis.forEvent(
            paths = listOf(path(0), path(1), path(2)),
            candidates = listOf(candidates("a"), candidates("b"), candidates("c")),
            touchDownAtMs = listOf(100L, 120L, 140L),
            graceWindowMs = 350L,
        )

        assertEquals(
            listOf(null, PathPair(0, 1), PathPair(0, 2), PathPair(1, 2)),
            hypotheses.map(MultiPathOrderHypothesis::swappedPair),
        )
        assertEquals(
            listOf(
                listOf("a", "b", "c"),
                listOf("b", "a", "c"),
                listOf("c", "b", "a"),
                listOf("a", "c", "b"),
            ),
            hypotheses.map { hypothesis -> hypothesis.candidates.map { it.single().word.word } },
        )
        assertEquals(listOf(40L, 40L, 40L, 40L), hypotheses.map { it.touchDownDeltaMs })
    }

    @Test
    fun `uses one event span and continuous timing weight for every competing hypothesis`() {
        val near = MultiPathOrderHypothesis.forEvent(
            paths = listOf(path(0), path(1)),
            candidates = listOf(candidates("a"), candidates("b")),
            touchDownAtMs = listOf(100L, 135L),
            graceWindowMs = 350L,
        )
        val wide = MultiPathOrderHypothesis.forEvent(
            paths = listOf(path(0), path(1)),
            candidates = listOf(candidates("a"), candidates("b")),
            touchDownAtMs = listOf(100L, 500L),
            graceWindowMs = 350L,
        )

        assertEquals(listOf(35L, 35L), near.map { it.touchDownDeltaMs })
        near.forEach { hypothesis ->
            assertEquals(0.9, hypothesis.languageEvidenceWeight, 0.000001)
        }
        assertEquals(listOf(400L, 400L), wide.map { it.touchDownDeltaMs })
        wide.forEach { hypothesis ->
            assertEquals(0.0, hypothesis.languageEvidenceWeight, 0.000001)
        }
    }

    @Test
    fun `deduplicates identical candidate orderings with observed provenance`() {
        val hypotheses = MultiPathOrderHypothesis.forEvent(
            paths = listOf(path(0), path(1)),
            candidates = listOf(candidates("same"), candidates("same")),
            touchDownAtMs = listOf(100L, 120L),
            graceWindowMs = 350L,
        )

        assertEquals(1, hypotheses.size)
        assertEquals(null, hypotheses.single().swappedPair)
        assertTrue(hypotheses.single().isObserved)
    }

    @Test
    fun `rejects malformed candidate or timestamp metadata deterministically`() {
        val paths = listOf(path(0), path(1))
        val candidateMismatch = assertThrows(IllegalArgumentException::class.java) {
            MultiPathOrderHypothesis.forEvent(
                paths = paths,
                candidates = listOf(candidates("a")),
                touchDownAtMs = listOf(100L, 120L),
                graceWindowMs = 350L,
            )
        }
        val timestampMismatch = assertThrows(IllegalArgumentException::class.java) {
            MultiPathOrderHypothesis.forEvent(
                paths = paths,
                candidates = listOf(candidates("a"), candidates("b")),
                touchDownAtMs = listOf(120L, 100L),
                graceWindowMs = 350L,
            )
        }

        assertEquals("Each path must have ranked candidates", candidateMismatch.message)
        assertEquals("Touch-down timestamps must be in observed order", timestampMismatch.message)
    }

    private fun path(id: Int) = GesturePath(listOf(GesturePoint(id.toFloat(), 0f, 0L)))

    private fun candidates(word: String) = listOf(ScoredCandidate(WordEntry(word, 1.0), 1.0))
}
