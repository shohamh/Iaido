package com.iaido.app

internal fun displayedReelIndex(selectedIndex: Int, dragOffsetSteps: Float, maxIndex: Int): Int {
    if (maxIndex < 0) return 0
    return (selectedIndex - dragOffsetSteps.roundToInt()).coerceIn(0, maxIndex)
}

internal fun reelVisibleSlotCount(candidateCount: Int): Int =
    candidateCount.coerceIn(1, MAX_REEL_VISIBLE_SLOTS)

internal fun reelCenterSlotOffset(visibleSlotCount: Int): Float =
    (visibleSlotCount - 1) / 2f

internal fun reelSettleOffset(displayedIndex: Int, selectedIndex: Int): Float =
    (selectedIndex - displayedIndex).toFloat()

/** The replacement group occupies every source or replacement slot it spans. */
internal fun replacementReelWidthSlots(sourceWordCount: Int, replacementWordCount: Int): Int =
    maxOf(sourceWordCount, replacementWordCount)

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()

internal const val MAX_REEL_VISIBLE_SLOTS = 3
