package com.iaido.core.perf

/** Correct words for sequence decoding are aligned to their swipe positions, not text LCS. */
internal fun positionalWordMatches(reference: List<String>, output: List<String>): Int =
    (0 until minOf(reference.size, output.size)).count { index -> reference[index] == output[index] }

/** Offline metrics for the per-swipe candidate lattice and revisions made by rolling decoding. */
internal class SequenceEvaluationMetrics(
    private val highConfidenceGap: Double = 1.0,
) {
    private var candidateCount = 0
    private var candidateHits = 0
    private var changedWords = 0
    private var harmfulRevisions = 0
    private var recoveredRevisions = 0
    private var correctHighConfidenceObservations = 0
    private var changedCorrectHighConfidence = 0

    init {
        require(highConfidenceGap >= 0.0)
    }

    fun observeCandidate(reference: String, candidates: Iterable<String>) {
        candidateCount++
        if (candidates.any { candidate -> candidate == reference }) candidateHits++
    }

    /** Compare the prior visible sequence with the new decode, aligned to the known reference. */
    fun observeRevision(
        previous: List<String>,
        current: List<String>,
        reference: List<String>,
        previousConfidenceGaps: List<Double>,
    ) {
        require(previous.size == previousConfidenceGaps.size)
        val comparableWords = minOf(previous.size, current.size, reference.size)
        for (index in 0 until comparableWords) {
            val before = previous[index]
            val after = current[index]
            val wasCorrect = before == reference[index]
            val wasHighConfidence = previousConfidenceGaps[index] >= highConfidenceGap
            if (wasCorrect && wasHighConfidence) correctHighConfidenceObservations++
            if (before == after) continue

            changedWords++
            if (wasCorrect) {
                harmfulRevisions++
                if (wasHighConfidence) changedCorrectHighConfidence++
            } else if (after == reference[index]) {
                recoveredRevisions++
            }
        }
    }

    fun add(other: SequenceEvaluationMetrics) {
        val snapshot = other.snapshot()
        candidateCount += snapshot.candidateCount
        candidateHits += snapshot.candidateHits
        changedWords += snapshot.changedWords
        harmfulRevisions += snapshot.harmfulRevisions
        recoveredRevisions += snapshot.recoveredRevisions
        correctHighConfidenceObservations += snapshot.correctHighConfidenceObservations
        changedCorrectHighConfidence += snapshot.changedCorrectHighConfidence
    }

    fun snapshot() = Snapshot(
        candidateCount = candidateCount,
        candidateHits = candidateHits,
        changedWords = changedWords,
        harmfulRevisions = harmfulRevisions,
        recoveredRevisions = recoveredRevisions,
        correctHighConfidenceObservations = correctHighConfidenceObservations,
        changedCorrectHighConfidence = changedCorrectHighConfidence,
    )

    data class Snapshot(
        val candidateCount: Int,
        val candidateHits: Int,
        val changedWords: Int,
        val harmfulRevisions: Int,
        val recoveredRevisions: Int,
        val correctHighConfidenceObservations: Int,
        val changedCorrectHighConfidence: Int,
    ) {
        val candidateRecall: Double
            get() = if (candidateCount == 0) 0.0 else 100.0 * candidateHits / candidateCount

        val highConfidenceChangeRate: Double
            get() = if (correctHighConfidenceObservations == 0) 0.0
            else 100.0 * changedCorrectHighConfidence / correctHighConfidenceObservations
    }
}
