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
internal const val CHIP_HORIZONTAL_PADDING_DP = 10f
internal const val MIN_CHIP_WIDTH_DP = 56f
internal const val MAX_CHIP_WIDTH_DP = 140f
internal const val REEL_ITEM_SPACING_DP = 8f

/** A chip's resting width: its selected word's measured width plus padding, clamped to a usable range. */
internal fun chipReservedWidthDp(measuredTextWidthDp: Float): Float =
    (measuredTextWidthDp + CHIP_HORIZONTAL_PADDING_DP * 2).coerceIn(MIN_CHIP_WIDTH_DP, MAX_CHIP_WIDTH_DP)
