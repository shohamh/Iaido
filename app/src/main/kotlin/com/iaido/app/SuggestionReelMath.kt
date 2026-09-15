package com.iaido.app

internal fun displayedReelIndex(selectedIndex: Int, dragOffsetSteps: Float, maxIndex: Int): Int {
    if (maxIndex < 0) return 0
    return (selectedIndex - dragOffsetSteps.roundToInt()).coerceIn(0, maxIndex)
}

internal fun reelVisibleSlotCount(candidateCount: Int): Int =
    SINGLE_REEL_VISIBLE_SLOT

internal fun reelSettleOffset(displayedIndex: Int, selectedIndex: Int): Float =
    (selectedIndex - displayedIndex).toFloat()

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()

private const val SINGLE_REEL_VISIBLE_SLOT = 1
