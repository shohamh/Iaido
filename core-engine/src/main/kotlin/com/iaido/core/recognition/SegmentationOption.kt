package com.iaido.core.recognition

/** A dictionary-valid replacement for a bounded run of completed gesture units. */
data class SegmentationOption(
    val words: List<String>,
    val score: Double,
    val sourceGestureIds: List<String>,
)
