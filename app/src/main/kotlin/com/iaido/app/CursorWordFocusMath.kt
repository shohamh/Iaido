package com.iaido.app

import kotlin.math.abs

/** Focus the containing word, or the nearest word when the cursor is in whitespace. */
internal fun <T> cursorFocusedWordIndex(
    words: List<T>,
    cursorPosition: Int,
    start: (T) -> Int,
    endExclusive: (T) -> Int,
    previousFocusedIndex: Int? = null,
): Int? {
    if (words.isEmpty()) return null
    val containing = words.indices.filter { index ->
        cursorPosition >= start(words[index]) && cursorPosition < endExclusive(words[index])
    }
    if (containing.isNotEmpty()) {
        return previousFocusedIndex?.takeIf(containing::contains) ?: containing.first()
    }

    val distances = words.indices.associateWith { index ->
        val wordStart = start(words[index])
        val wordEnd = endExclusive(words[index])
        when {
            cursorPosition < wordStart -> wordStart - cursorPosition
            cursorPosition > wordEnd -> cursorPosition - wordEnd
            else -> 0
        }
    }
    val minimumDistance = distances.values.minOrNull() ?: return null
    val nearest = distances.filterValues { it == minimumDistance }.keys
    if (previousFocusedIndex in nearest) return previousFocusedIndex
    return nearest.lastOrNull { endExclusive(words[it]) <= cursorPosition } ?: nearest.first()
}
