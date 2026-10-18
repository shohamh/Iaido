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

    // The shipped dictionary stores frequency as a probability (all entries are well below 1.0;
    // the most common English word, "the", is ~0.054). A floor of 1.0 clamped every real entry to
    // the same value, making ln(frequency) zero for every word and silently disabling the
    // frequency term. The floor only needs to stay below the smallest real frequency so ln()
    // never sees zero/negative input.
    /** Frequency floor used before applying the logarithm. */
    const val MIN_FREQUENCY: Double = 1e-9

    /** Weight applied to the raw DTW-style shape distance term. */
    const val SHAPE_DISTANCE_WEIGHT: Double = 2.1

    /** Weight applied to the corner-matching bonus/penalty term. */
    const val CORNER_BONUS_WEIGHT: Double = 0.5

    /** Weight applied to log(frequency) in the final combined score. */
    const val FREQUENCY_WEIGHT: Double = 0.5

    /**
     * Weight applied to the distance between the swipe's first/last point and the word's first/last
     * key centre (normalized layout units, summed over both ends).
     */
    const val ENDPOINT_WEIGHT: Double = 22.0

    /** Scale applied to the n-gram context score when it is added to a recognized candidate. */
    const val CONTEXT_WEIGHT: Double = 0.6

    /** Number of [1,2,1] smoothing passes applied to the user path before resampling. */
    const val SMOOTHING_PASSES: Int = 2

    /** Smoothing applies only when the mean gap between touch points is below this (normalized, ~half a key). */
    const val SMOOTHING_MAX_MEAN_SEGMENT: Double = 0.05

    /** Score cost per key width of path trimmed off a swipe's start/end when reading it past a stray touch. */
    const val TRIM_PENALTY_PER_KEY: Double = 2.0

    /** Candidate amounts (in key widths) trimmed from each end when looking for a better-aligned reading. */
    val TRIM_STEPS_KEYS: DoubleArray = doubleArrayOf(0.0, 0.8, 1.6)

    /** Extra frequency weight applied by [InferenceSegmenter] on top of the recognizer's own frequency term. */
    const val SEGMENTER_FREQUENCY_WEIGHT: Double = 0.1

    /**
     * Frequency weight [InferenceSegmenter] gives the 2nd and later word of a split. NKG2 n-gram scores are
     * log-ratios against the unigram prior, so every extra word earns context credit; the split has to pay its
     * unigram cost at a comparable scale to the bigram and trigram weights below, otherwise splits earn more credit
     * than they cost and common words fragment ("today of" -> "to day of"). The first/only word keeps
     * [SEGMENTER_FREQUENCY_WEIGHT] because the recognizer already scored the whole swipe. Tuned with the context
     * weights on SegmentationBenchmarkTest.
     */
    const val SEGMENTER_EXTRA_WORD_FREQUENCY_WEIGHT: Double = 0.3

    /** Bigram weight of the context scorer [InferenceSegmenter] is built with in the app. */
    const val SEGMENTER_BIGRAM_WEIGHT: Double = 0.3

    /** Trigram weight of the context scorer [InferenceSegmenter] is built with in the app. */
    const val SEGMENTER_TRIGRAM_WEIGHT: Double = 0.3

    /**
     * Trimmed readings are only tried for candidates whose untrimmed score is within this of the best one
     * (a pure speed bound: on the synthetic benchmark a gate of 6 already changes no ranking).
     */
    const val TRIM_GATE: Double = 12.0
}
