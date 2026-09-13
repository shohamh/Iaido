package com.ninjakeys.core.recognition

/**
 * Single home for gesture-recognition tunable weights, per the
 * gesture-recognition-architecture ticket's requirement that these never
 * be inline magic numbers. These are internal developer-tuning constants,
 * not user-facing settings (see the settings-app-ux ticket).
 */
object ScoringConstants {
    /** Weight applied to the raw DTW-style shape distance term. */
    const val SHAPE_DISTANCE_WEIGHT: Double = 1.0

    /** Weight applied to the corner-matching bonus/penalty term. */
    const val CORNER_BONUS_WEIGHT: Double = 0.5

    /** Weight applied to log(frequency) in the final combined score. */
    const val FREQUENCY_WEIGHT: Double = 0.1
}
