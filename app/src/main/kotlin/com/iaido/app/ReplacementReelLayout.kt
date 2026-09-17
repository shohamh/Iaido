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

/** Resolves the prior selection against refreshed candidates by stable replacement ID. */
internal fun replacementSelectedIndex(
    options: List<ReplacementOption>,
    selectedOptionId: String?,
): Int = options.indexOfFirst { it.id == selectedOptionId }
    .takeIf { it >= 0 }
    ?: 0

internal class ReplacementReelSelection(
    initialOptions: List<ReplacementOption> = emptyList(),
    initialSelectedOptionId: String? = null,
) {
    private var options: List<ReplacementOption> = initialOptions
    var selectedOptionId: String? = initialSelectedOptionId
        private set
    var committed: ReplacementOption? = null
        private set
    var isPreviewing: Boolean = false
        private set

    // Snapshot of selectedOptionId from just before the *current* preview session started (taken
    // once, on the first preview() call after isPreviewing was false) -- restored by cancel() so
    // an abandoned drag leaves the reel showing whatever it showed before that drag began, rather
    // than permanently remembering the highest index the (cancelled) drag happened to reach. A
    // released drag intentionally does NOT restore this: committing IS meant to move the
    // remembered selection forward.
    private var idBeforePreview: String? = initialSelectedOptionId

    fun updateOptions(options: List<ReplacementOption>) {
        this.options = options
        if (selectedOptionId != null && options.none { it.id == selectedOptionId }) {
            selectedOptionId = null
        }
    }

    fun selectedIndex(): Int = replacementSelectedIndex(options, selectedOptionId)

    fun selectedOption(): ReplacementOption? = options.getOrNull(selectedIndex())

    fun preview(index: Int): ReplacementOption? = options.getOrNull(index)?.also(::selectPreview)

    fun preview(option: ReplacementOption): ReplacementOption? =
        options.firstOrNull { it.id == option.id }?.also(::selectPreview)

    fun release(index: Int): ReplacementOption? = options.getOrNull(index)?.also(::selectRelease)

    fun release(option: ReplacementOption): ReplacementOption? =
        options.firstOrNull { it.id == option.id }?.also(::selectRelease)

    fun cancel() {
        if (isPreviewing) selectedOptionId = idBeforePreview
        isPreviewing = false
    }

    private fun selectPreview(option: ReplacementOption) {
        if (!isPreviewing) idBeforePreview = selectedOptionId
        selectedOptionId = option.id
        isPreviewing = true
    }

    private fun selectRelease(option: ReplacementOption) {
        selectedOptionId = option.id
        committed = option
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
