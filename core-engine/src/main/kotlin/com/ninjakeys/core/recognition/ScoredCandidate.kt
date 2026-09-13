package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry

data class ScoredCandidate(
    val word: WordEntry,
    val score: Double,
)
