package com.iaido.app

import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SuggestionChip
import java.util.Locale

internal fun displayedReelIndex(selectedIndex: Int, dragOffsetSteps: Float, maxIndex: Int): Int {
    if (maxIndex < 0) return 0
    return (selectedIndex - dragOffsetSteps.roundToInt()).coerceIn(0, maxIndex)
}

internal fun reelRenderOffset(
    offset: Float,
    minOffset: Float,
    maxOffset: Float,
    isDragging: Boolean,
    isSettling: Boolean,
): Float = if (isDragging || isSettling) {
    offset.coerceIn(minOffset, maxOffset)
} else {
    0f
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

internal data class InlineReplacementReel(
    val chip: SuggestionChip,
    val options: List<ReplacementOption>,
    val wordIndex: Int,
)

internal fun inlineReplacementOptionIds(options: List<ReplacementOption>): Set<String> {
    val sourceWords = options.firstOrNull()?.sourceWords.orEmpty()
    if (sourceWords.isEmpty()) return emptySet()
    return options
        .filter { it.sourceWords == sourceWords && it.replacementWords.size == sourceWords.size }
        .map(ReplacementOption::id)
        .toSet()
}

internal fun edgeReplacementOptions(
    options: List<ReplacementOption>,
    liveReplacementOptionIds: Set<String>,
    attachedOptionIds: Set<String> = emptySet(),
): List<ReplacementOption> = options.filterNot { option ->
    val attachedSingleSourceSplit = option.id in attachedOptionIds &&
        option.sourceWords.size == 1 && option.replacementWords.size > 1
    attachedSingleSourceSplit || (
        option.sourceWords.size > 1 && option.replacementWords.size == 1 &&
            option.id !in liveReplacementOptionIds
    )
}

/**
 * Builds one ordinary word reel for each word in a live same-shaped inference option group.
 * Same-shaped options stay in one ordinary word reel. Structural splits and multi-word joins
 * remain in the grouped reel where they can be previewed and committed as one replacement.
 */
internal fun inlineReplacementReels(options: List<ReplacementOption>): List<InlineReplacementReel> {
    val sourceWords = options.firstOrNull()?.sourceWords.orEmpty()
    if (sourceWords.isEmpty()) return emptyList()
    val inlineOptionIds = inlineReplacementOptionIds(options)
    val sameShape = options
        .filter { it.id in inlineOptionIds }
        .filterNot { option ->
            sourceWords.size == 1 && sourceWords.single().length > 1 &&
                option.replacementWords.single().length == 1
        }
        .distinctBy(ReplacementOption::id)
    if (sameShape.isEmpty()) return emptyList()

    return sourceWords.indices.map { wordIndex ->
        val wordOptions = sameShape
            .filter { it.replacementWords.getOrNull(wordIndex) != null }
            .distinctBy { it.replacementWords[wordIndex] }
        val candidates = (wordOptions.map { it.replacementWords[wordIndex] } + sourceWords[wordIndex]).distinct()
        InlineReplacementReel(
            chip = SuggestionChip(
                word = sourceWords[wordIndex],
                alternatives = candidates,
                selectedIndex = candidates.indexOf(sourceWords[wordIndex]).coerceAtLeast(0),
                id = LIVE_REEL_ID_BASE - wordIndex,
            ),
            options = wordOptions,
            wordIndex = wordIndex,
        )
    }
}

internal fun displayCandidateForIndex(chip: SuggestionChip, index: Int): String? =
    (chip.alternatives + chip.word).distinct().getOrNull(index)

internal fun InlineReplacementReel.optionForDisplayIndex(index: Int): ReplacementOption? {
    val candidate = displayCandidateForIndex(chip, index) ?: return null
    return options.firstOrNull { it.replacementWords.getOrNull(wordIndex) == candidate }
}

internal fun replacementScoreForWord(
    options: List<ReplacementOption>,
    wordIndex: Int,
    word: String,
): Double? = options
    .filter { it.replacementWords.getOrNull(wordIndex) == word }
    .maxOfOrNull(ReplacementOption::score)

internal fun candidateScoreLabel(score: Double): String =
    String.format(Locale.US, "%.3f", score)

private fun Float.roundToInt(): Int = kotlin.math.round(this).toInt()

internal const val MAX_REEL_VISIBLE_SLOTS = 3
internal const val CHIP_HORIZONTAL_PADDING_DP = 10f
internal const val MIN_CHIP_WIDTH_DP = 56f
internal const val REEL_ITEM_SPACING_DP = 8f
private const val LIVE_REEL_ID_BASE = -1_000_000

/** A chip's resting width: its widest candidate's measured width plus padding. */
internal fun chipReservedWidthDp(measuredTextWidthDp: Float): Float =
    (measuredTextWidthDp + CHIP_HORIZONTAL_PADDING_DP * 2).coerceAtLeast(MIN_CHIP_WIDTH_DP)

internal fun widestChipReservedWidthDp(measuredTextWidthsDp: List<Float>): Float =
    chipReservedWidthDp(measuredTextWidthsDp.maxOrNull() ?: 0f)

internal const val REPLACEMENT_WORD_HORIZONTAL_PADDING_DP = 5f
internal const val MIN_REPLACEMENT_WORD_WIDTH_DP = 44f

/**
 * A replacement-reel word's resting width: its measured width plus tight padding, clamped so a
 * single letter isn't stretched into a wide box while a long word still gets room to be read.
 */
internal fun replacementWordReservedWidthDp(measuredTextWidthDp: Float): Float =
    (measuredTextWidthDp + REPLACEMENT_WORD_HORIZONTAL_PADDING_DP * 2)
        .coerceAtLeast(MIN_REPLACEMENT_WORD_WIDTH_DP)

/**
 * The width to actually draw a reel row at. Never below [reservedWidthDp] (its slot's resting
 * width) and, when a neighbor exists to visually overlap, never above [reservedWidthDp] plus
 * [neighborReservedWidthDp] plus one item-spacing gap. With no neighbor to overlap, the row is
 * capped at its own reserved width (the caller's Text then ellipsizes it).
 */
internal fun overflowDrawWidthDp(
    naturalWidthDp: Float,
    reservedWidthDp: Float,
    neighborReservedWidthDp: Float?,
): Float {
    if (neighborReservedWidthDp == null) return reservedWidthDp
    val cap = reservedWidthDp + neighborReservedWidthDp + REEL_ITEM_SPACING_DP
    return naturalWidthDp.coerceIn(reservedWidthDp, cap)
}

/**
 * Index, within the chip-only portion of the strip (in the same order [SuggestionStrip] already
 * renders chips), of the newest chip: last for left-to-right, first for right-to-left, matching
 * `ordered = if (rtl) chips.asReversed() else chips`.
 */
internal fun autoScrollTargetIndex(chipCount: Int, rtl: Boolean): Int {
    if (chipCount <= 0) return 0
    return if (rtl) 0 else chipCount - 1
}
