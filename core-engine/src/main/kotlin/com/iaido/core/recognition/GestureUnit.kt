package com.iaido.core.recognition

import com.iaido.core.gesture.GesturePath

/**
 * A completed swipe input kept stable while recent spacing is re-inferred.
 * Multi-path units carry one to four paths from one completed gesture event; ordinary units
 * contain one path. Touch-down order is the observed candidate assignment.
 */
data class GestureUnit(
    val id: String,
    val paths: List<GesturePath>,
    val candidates: List<List<ScoredCandidate>>,
    val concurrent: Boolean,
    val touchDownAtMs: List<Long> = List(paths.size) { index -> index.toLong() },
    val graceWindowMs: Long = DEFAULT_GRACE_WINDOW_MS,
) {
    init {
        require(id.isNotBlank()) { "Gesture unit ID must not be blank" }
        require(paths.size in 1..4) { "Gesture unit must contain one to four paths" }
        require(candidates.size == paths.size) { "Each path must have ranked candidates" }
        require(touchDownAtMs.size == paths.size) { "Each path must have a touch-down timestamp" }
        require(touchDownAtMs.zipWithNext().all { (previous, current) -> previous <= current }) {
            "Touch-down timestamps must be in observed order"
        }
        require(graceWindowMs >= 0L) { "Grace window must not be negative" }
        require(if (concurrent) paths.size in 2..4 else paths.size == 1) {
            "Multi-path units require two to four paths and sequential units require one"
        }
    }

    private companion object {
        const val DEFAULT_GRACE_WINDOW_MS = 350L
    }
}
