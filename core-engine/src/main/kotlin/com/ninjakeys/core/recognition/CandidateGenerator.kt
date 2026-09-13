package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.layout.KeyboardLayout

interface CandidateGenerator {
    fun generateCandidates(
        path: GesturePath,
        layout: KeyboardLayout,
        dictionary: List<WordEntry>,
    ): List<WordEntry>
}
