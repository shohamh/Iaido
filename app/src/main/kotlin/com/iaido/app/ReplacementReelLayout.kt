package com.iaido.app

import com.iaido.core.recognition.ReplacementOption

internal data class ReplacementReelLayout(
    val sourceSlotCount: Int,
    val reelCount: Int,
    val widthSlots: Int,
    val isJoined: Boolean,
    val renderedWords: List<String>,
)

/** Pure layout contract for the grouped suggestion renderer. */
internal fun replacementReelLayout(option: ReplacementOption, rtl: Boolean): ReplacementReelLayout {
    val joined = option.sourceWords.size > 1 && option.replacementWords.size == 1
    val reelCount = if (joined) 1 else option.replacementWords.size
    return ReplacementReelLayout(
        sourceSlotCount = option.sourceWords.size,
        reelCount = reelCount,
        widthSlots = replacementReelWidthSlots(option.sourceWords.size, option.replacementWords.size),
        isJoined = joined,
        renderedWords = if (rtl) option.replacementWords.asReversed() else option.replacementWords,
    )
}

internal class ReplacementReelSelection(private val options: List<ReplacementOption>) {
    var committed: ReplacementOption? = null
        private set
    var isPreviewing: Boolean = false
        private set

    fun preview(index: Int): ReplacementOption? = options.getOrNull(index)?.also { isPreviewing = true }

    fun release(index: Int): ReplacementOption? = options.getOrNull(index)?.also {
        committed = it
        isPreviewing = false
    }

    fun cancel() {
        isPreviewing = false
    }
}

internal fun replacementReelDescription(
    option: ReplacementOption,
    candidateIndex: Int,
    candidateCount: Int,
): String = "Iaido replacement: ${option.sourceWords.size} source ${pluralWord(option.sourceWords.size)} " +
    "to ${option.replacementWords.size} replacement ${pluralWord(option.replacementWords.size)}; " +
    "current words ${option.sourceWords.joinToString(" ")}; candidate ${candidateIndex + 1} of $candidateCount"

internal fun replacementCursorAfterCommit(spanStart: Int, replacementWords: List<String>): Int =
    spanStart + replacementWords.joinToString(" ").length

private fun pluralWord(count: Int): String = if (count == 1) "word" else "words"
