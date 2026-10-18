package com.iaido.core.perf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SequenceEvaluationMetricsTest {
    @Test
    fun `sequence word accuracy compares each output to its swipe position`() {
        assertEquals(0, positionalWordMatches(listOf("a", "b"), listOf("b", "a")))
        assertEquals(2, positionalWordMatches(listOf("a", "b", "c"), listOf("a", "wrong", "c")))
    }

    @Test
    fun `candidate recall counts truth present anywhere in swipe candidates`() {
        val metrics = SequenceEvaluationMetrics()

        metrics.observeCandidate("today", listOf("to", "today", "day"))
        metrics.observeCandidate("of", listOf("off", "if"))

        val snapshot = metrics.snapshot()
        assertEquals(2, snapshot.candidateCount)
        assertEquals(1, snapshot.candidateHits)
    }

    @Test
    fun `revision metrics count harmful changes and formerly correct high confidence words`() {
        val metrics = SequenceEvaluationMetrics(highConfidenceGap = 1.0)

        metrics.observeRevision(
            previous = listOf("today", "of"),
            current = listOf("to", "of"),
            reference = listOf("today", "of"),
            previousConfidenceGaps = listOf(1.5, 1.2),
        )

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.changedWords)
        assertEquals(1, snapshot.harmfulRevisions)
        assertEquals(0, snapshot.recoveredRevisions)
        assertEquals(2, snapshot.correctHighConfidenceObservations)
        assertEquals(1, snapshot.changedCorrectHighConfidence)
    }

    @Test
    fun `revision metrics count corrections without calling them harmful`() {
        val metrics = SequenceEvaluationMetrics(highConfidenceGap = 1.0)

        metrics.observeRevision(
            previous = listOf("tody"),
            current = listOf("today"),
            reference = listOf("today"),
            previousConfidenceGaps = listOf(0.4),
        )

        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.changedWords)
        assertEquals(0, snapshot.harmfulRevisions)
        assertEquals(1, snapshot.recoveredRevisions)
        assertEquals(0, snapshot.correctHighConfidenceObservations)
        assertEquals(0, snapshot.changedCorrectHighConfidence)
    }
}
