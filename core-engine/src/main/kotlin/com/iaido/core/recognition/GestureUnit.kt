package com.iaido.core.recognition

import com.iaido.core.gesture.GesturePath

/**
 * A completed swipe input kept stable while recent spacing is re-inferred.
 * Concurrent units contain exactly two paths; ordinary units contain one.
 */
data class GestureUnit(
    val id: String,
    val paths: List<GesturePath>,
    val candidates: List<List<ScoredCandidate>>,
    val concurrent: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Gesture unit ID must not be blank" }
        require(paths.size in 1..2) { "Gesture unit must contain one or two paths" }
        require(candidates.size == paths.size) { "Each path must have ranked candidates" }
        require(if (concurrent) paths.size == 2 else paths.size == 1) {
            "Concurrent units require two paths and sequential units require one"
        }
    }
}
