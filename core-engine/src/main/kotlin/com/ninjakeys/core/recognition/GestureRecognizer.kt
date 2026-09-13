package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.layout.KeyboardLayout

/**
 * The single entry point the IME (Stage 2 onward) calls per completed
 * swipe. Composes a swappable [CandidateGenerator] and [PathScorer] --
 * either can be replaced independently without changing callers.
 */
class GestureRecognizer(
    private val candidateGenerator: CandidateGenerator,
    private val pathScorer: PathScorer,
) {
    fun recognize(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<ScoredCandidate> {
        val candidates = candidateGenerator.generateCandidates(path, layout, dictionary)
        return pathScorer.score(path, candidates, layout)
    }
}
