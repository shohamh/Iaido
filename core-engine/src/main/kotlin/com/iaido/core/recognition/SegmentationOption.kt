package com.iaido.core.recognition

/** A dictionary-valid replacement for a bounded run of completed gesture units. */
data class SegmentationOption(
    val words: List<String>,
    val score: Double,
    val sourceGestureIds: List<String>,
    val hypothesisMetadata: List<HypothesisMetadata> = emptyList(),
)

/** Provenance for a multi-path ordering decision retained for diagnostics and tests. */
data class HypothesisMetadata(
    val swappedPair: PathPair?,
    val touchDownDeltaMs: Long,
    val languageEvidenceWeight: Double,
)
