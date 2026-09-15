package com.iaido.app

/**
 * Sentence-start capitalization rule shared by every text-commit path
 * (tap-typed commits in [TypingController] and inferred swipe commits in
 * [SwipeTypingCoordinator]) so they all capitalize identically.
 */
internal object SentenceCapitalization {
    /** True when text typed immediately after [before] starts a new sentence. */
    fun needsCapitalization(before: String): Boolean =
        before.isEmpty() || before.matches(Regex(".*[.!?]\\s*$"))

    /** Uppercases [value]'s first letter, leaving it unchanged if it has none. */
    fun capitalizeFirstLetter(value: String): String =
        if (value.isEmpty() || !value.first().isLetter()) value else value.replaceFirstChar { it.uppercase() }

    /** Uppercases [value]'s first letter only when [before] needs a new sentence. */
    fun capitalizeIfNeeded(value: String, before: String): String =
        if (!needsCapitalization(before)) value else capitalizeFirstLetter(value)
}
