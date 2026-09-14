package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry

data class ScoredCandidate(
    val word: WordEntry,
    val score: Double,
)
