package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout

interface PathScorer {
    /** Returns candidates ranked descending by score (best match first). */
    fun score(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate>

    /**
     * Same scores as [score] in no guaranteed order, for callers that re-rank the results
     * themselves and would otherwise sort twice.
     */
    fun scoreUnordered(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate> = score(path, candidates, layout)
}
