package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.layout.KeyboardLayout

interface PathScorer {
    /** Returns candidates ranked descending by score (best match first). */
    fun score(
        path: GesturePath,
        candidates: List<WordEntry>,
        layout: KeyboardLayout,
    ): List<ScoredCandidate>
}
