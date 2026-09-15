package com.iaido.app

import kotlin.math.abs
internal enum class BackspaceGestureAction {
    TAP,
    DELETE,
    UNDO,
    REDO,
}

internal fun classifyBackspaceGesture(dx: Float, dy: Float, threshold: Float): BackspaceGestureAction {
    if (maxOf(abs(dx), abs(dy)) < threshold) return BackspaceGestureAction.TAP
    return if (abs(dx) >= abs(dy)) {
        if (dx < 0f) BackspaceGestureAction.DELETE else BackspaceGestureAction.TAP
    } else if (dy < 0f) {
        BackspaceGestureAction.UNDO
    } else {
        BackspaceGestureAction.REDO
    }
}

internal fun backspaceRepeatIntervalMs(repeatCount: Int): Long =
    (BACKSPACE_INITIAL_INTERVAL_MS - repeatCount.coerceAtLeast(0) * BACKSPACE_ACCELERATION_MS)
        .coerceAtLeast(BACKSPACE_MIN_INTERVAL_MS)

internal fun deletionCountForSwipe(
    requestedCount: Int,
    textBeforeCursor: String,
    maxCharacters: Int,
): Int {
    if (requestedCount <= 0 || textBeforeCursor.isEmpty() || maxCharacters <= 0) return 0
    val safeMaximum = minOf(maxCharacters, textBeforeCursor.length)
    val maximumBoundary = wordBoundaryDeletionCounts(textBeforeCursor)
        .filter { it <= safeMaximum }
        .maxOrNull() ?: safeMaximum
    val rawCount = requestedCount.coerceIn(0, safeMaximum)
    val nearestBoundary = wordBoundaryDeletionCounts(textBeforeCursor)
        .filter { it <= maximumBoundary }
        .minByOrNull { abs(it - rawCount) }
    return if (nearestBoundary != null && abs(nearestBoundary - rawCount) <= WORD_BOUNDARY_SNAP_CHARS) {
        nearestBoundary
    } else {
        rawCount.coerceAtMost(maximumBoundary)
    }
}

private fun wordBoundaryDeletionCounts(textBeforeCursor: String): List<Int> = buildList {
    for (deletionCount in 0..textBeforeCursor.length) {
        val endpoint = textBeforeCursor.length - deletionCount
        if (endpoint == 0 || endpoint < textBeforeCursor.length && textBeforeCursor[endpoint].isWhitespace()) {
            add(deletionCount)
        }
    }
}

private const val BACKSPACE_INITIAL_INTERVAL_MS = 200L
private const val BACKSPACE_ACCELERATION_MS = 15L
private const val BACKSPACE_MIN_INTERVAL_MS = 70L
private const val WORD_BOUNDARY_SNAP_CHARS = 1
