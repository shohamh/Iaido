package com.iaido.core.recognition

/**
 * Single home for gesture-recognition tunable weights, per the
 * gesture-recognition-architecture ticket's requirement that these never
 * be inline magic numbers. These are internal developer-tuning constants,
 * not user-facing settings (see the settings-app-ux ticket).
 */
object ScoringConstants {
    /** Number of equally spaced samples used for shape comparison. */
    const val RESAMPLE_POINT_COUNT: Int = 32

    /** Maximum normalized distance for a path point to match a key center. */
    const val PROXIMITY_THRESHOLD: Float = 0.15f

    /** Minimum cross-product magnitude that identifies a direction change. */
    const val CORNER_CROSS_PRODUCT_THRESHOLD: Float = 0.3f

    /** Maximum sample-index distance for two corners to be considered matched. */
    const val CORNER_MATCH_INDEX_TOLERANCE: Int = 2

    /** Frequency floor used before applying the logarithm. */
    const val MIN_FREQUENCY: Double = 1.0

    /** Weight applied to the raw DTW-style shape distance term. */
    const val SHAPE_DISTANCE_WEIGHT: Double = 1.0

    /** Weight applied to the corner-matching bonus/penalty term. */
    const val CORNER_BONUS_WEIGHT: Double = 0.5

    /** Weight applied to log(frequency) in the final combined score. */
    const val FREQUENCY_WEIGHT: Double = 0.1
}
