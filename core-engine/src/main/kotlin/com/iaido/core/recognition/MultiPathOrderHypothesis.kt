package com.iaido.core.recognition

import com.iaido.core.gesture.GesturePath

/** Identifies the two observed path positions exchanged by a reorder hypothesis. */
data class PathPair(
    val firstIndex: Int,
    val secondIndex: Int,
) {
    init {
        require(firstIndex >= 0 && firstIndex < secondIndex) {
            "Path pairs must use distinct ascending indices"
        }
    }
}

/**
 * One ordering considered for paths from a single completed multi-path event.
 *
 * The observed assignment is always included. Each additional assignment exchanges exactly one
 * unordered pair, so paths from distinct gesture events can never be reordered together.
 */
data class MultiPathOrderHypothesis(
    val paths: List<GesturePath>,
    val candidates: List<List<ScoredCandidate>>,
    val swappedPair: PathPair?,
    val touchDownDeltaMs: Long,
    val languageEvidenceWeight: Double,
) {
    val isObserved: Boolean
        get() = swappedPair == null

    companion object {
        /**
         * Uses the event span `lastTouchDown - firstTouchDown` for every competing order and
         * applies `1 - clamp(span / graceWindow, 0, 1)` to language evidence. Path-fit scores are
         * deliberately not scaled by this event-level timing value.
         */
        fun forEvent(
            paths: List<GesturePath>,
            candidates: List<List<ScoredCandidate>>,
            touchDownAtMs: List<Long>,
            graceWindowMs: Long,
        ): List<MultiPathOrderHypothesis> {
            require(paths.size in 1..4) { "Multi-path events must contain one to four paths" }
            require(candidates.size == paths.size) { "Each path must have ranked candidates" }
            require(touchDownAtMs.size == paths.size) { "Each path must have a touch-down timestamp" }
            require(touchDownAtMs.zipWithNext().all { (previous, current) -> previous <= current }) {
                "Touch-down timestamps must be in observed order"
            }
            require(graceWindowMs >= 0L) { "Grace window must not be negative" }

            val eventSpanMs = touchDownAtMs.last() - touchDownAtMs.first()
            val eventLanguageEvidenceWeight = languageEvidenceWeight(eventSpanMs, graceWindowMs)
            val observed = MultiPathOrderHypothesis(
                paths = paths,
                candidates = candidates,
                swappedPair = null,
                touchDownDeltaMs = eventSpanMs,
                languageEvidenceWeight = eventLanguageEvidenceWeight,
            )
            val swaps = buildList {
                for (firstIndex in 0 until paths.lastIndex) {
                    for (secondIndex in firstIndex + 1 until paths.size) {
                        val pair = PathPair(firstIndex, secondIndex)
                        add(
                            MultiPathOrderHypothesis(
                                paths = paths.swapped(firstIndex, secondIndex),
                                candidates = candidates.swapped(firstIndex, secondIndex),
                                swappedPair = pair,
                                touchDownDeltaMs = eventSpanMs,
                                languageEvidenceWeight = eventLanguageEvidenceWeight,
                            ),
                        )
                    }
                }
            }

            return (listOf(observed) + swaps)
                .groupBy { hypothesis -> hypothesis.candidateOrderKey() }
                .map { (_, duplicates) ->
                    duplicates.maxWithOrNull(
                        compareBy<MultiPathOrderHypothesis> { it.isObserved }
                            .thenBy { it.languageEvidenceWeight },
                    )!!
                }
        }

        private fun languageEvidenceWeight(deltaMs: Long, graceWindowMs: Long): Double = when {
            graceWindowMs == 0L -> if (deltaMs == 0L) 1.0 else 0.0
            else -> (1.0 - deltaMs.toDouble() / graceWindowMs).coerceIn(0.0, 1.0)
        }

        private fun MultiPathOrderHypothesis.candidateOrderKey(): List<List<String>> =
            candidates.map { ranked -> ranked.map { candidate -> candidate.word.word } }

        private fun <T> List<T>.swapped(firstIndex: Int, secondIndex: Int): List<T> =
            toMutableList().also { values ->
                val first = values[firstIndex]
                values[firstIndex] = values[secondIndex]
                values[secondIndex] = first
            }
    }
}
