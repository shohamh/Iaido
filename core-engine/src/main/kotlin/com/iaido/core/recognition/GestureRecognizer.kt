package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout

/**
 * The single entry point the IME (Stage 2 onward) calls per completed
 * swipe. Composes a swappable [CandidateGenerator] and [PathScorer] --
 * either can be replaced independently without changing callers.
 */
class GestureRecognizer(
    private val candidateGenerator: CandidateGenerator,
    private val pathScorer: PathScorer,
    private val contextScorer: NgramContextScorer? = null,
) {
    fun recognize(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
        previousWords: List<String> = emptyList(),
    ): List<ScoredCandidate> {
        val candidates = candidateGenerator.generateCandidates(path, layout, dictionary)
        val shaped = pathScorer.score(path, candidates, layout)
        return contextScorer?.let { scorer ->
            shaped.map { result ->
                result.copy(score = result.score + scorer.score(previousWords, result.word.word))
            }.sortedByDescending { it.score }
        } ?: shaped
    }
}
