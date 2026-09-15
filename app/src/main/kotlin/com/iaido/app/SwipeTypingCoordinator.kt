package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.GestureUnit
import com.iaido.core.recognition.InferenceSegmenter
import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.ScoredCandidate
import com.iaido.core.recognition.SegmentationOption
import com.iaido.core.recognition.SplitWordParts
import com.iaido.core.typing.SpacingMode

sealed interface SplitPollOutcome {
    data class Resolved(val parts: SplitWordParts) : SplitPollOutcome
    data object Pending : SplitPollOutcome
    data object Cancelled : SplitPollOutcome
}

/** Coordinates completed recognition units with one editable host-text span. */
class SwipeTypingCoordinator(
    private val spacingMode: () -> SpacingMode,
    private val recognize: (GesturePath, KeyboardLayout) -> List<ScoredCandidate>,
    private val dictionary: () -> List<WordEntry>,
    private val previousWords: () -> List<String>,
    cursorPosition: () -> Int,
    replaceHostSpan: (HostTextSpan, String) -> Boolean,
    private val commitCompletedText: (String) -> Unit = { text ->
        val cursor = cursorPosition()
        replaceHostSpan(HostTextSpan(cursor, cursor), text)
    },
    onFinalizedWords: (HostTextSpan, List<String>, List<SegmentationOption>) -> Unit = { _, _, _ -> },
    private val textBeforeCursor: () -> String = { "" },
    private val hasFollowingWhitespace: () -> Boolean = { false },
    private val pollSplitParts: (Long) -> SplitWordParts? = { null },
    private val isSplitPending: () -> Boolean = { false },
    private val segmenter: InferenceSegmenter = InferenceSegmenter(),
    private val onReplacementOptionsChanged: (List<ReplacementOption>) -> Unit = {},
) {
    private val transaction = SwipeInferenceTransaction(
        cursorPosition = cursorPosition,
        replaceHostSpan = replaceHostSpan,
        onFinalized = onFinalizedWords,
    )
    private var nextUnitId = 0L
    private var replacementSelection: ReplacementSelection? = null

    /**
     * Whether the transaction currently in progress still owes a
     * sentence-start capitalization to its (original) first word. Captured
     * once when a fresh transaction starts (`transaction.sourceSpan == null`)
     * from [textBeforeCursor] at that moment, and consumed (reset to false)
     * once that first word is finalized — via a window slide or clear — so a
     * later, now mid-sentence, word is never capitalized.
     */
    private var capitalizeFirstWord = false

    fun onSingleSwipe(path: GesturePath, layout: KeyboardLayout) {
        onRecognizedSingleSwipe(path, recognize(path, layout))
    }

    fun onRecognizedSingleSwipe(path: GesturePath, candidates: List<ScoredCandidate>) {
        if (candidates.isEmpty()) return onRecognitionFailed()
        accept(GestureUnit(nextId(), listOf(path), listOf(candidates), concurrent = false))
    }

    fun onTwoFingerResult(parts: List<GesturePath>, layout: KeyboardLayout) {
        onRecognizedTwoFingerResult(parts, parts.map { path -> recognize(path, layout) })
    }

    fun onRecognizedTwoFingerResult(
        parts: List<GesturePath>,
        candidates: List<List<ScoredCandidate>>,
    ) {
        if (parts.size != 2) return onRecognitionFailed()
        if (candidates.size != parts.size || candidates.any { it.isEmpty() }) return onRecognitionFailed()
        accept(GestureUnit(nextId(), parts, candidates, concurrent = true))
    }

    fun onNonSwipeInput() = finalizeAndClear()

    fun onCursorMoved() = finalizeAndClear()

    fun onExternalEdit() = finalizeAndClear()

    fun onRecognitionFailed() = finalizeAndClear()

    /** Complete structured candidates for the active inference span. */
    fun replacementOptions(): List<ReplacementOption> {
        val selection = replacementSelection ?: activeReplacementSelection() ?: return emptyList()
        return selection.alternatives
            .map { option -> ReplacementOption(selection.sourceWords, option.words, option.score) }
            .distinctBy(ReplacementOption::id)
    }

    /** Replaces the active span with a candidate while its reel is being dragged. */
    fun previewReplacement(option: ReplacementOption): Boolean {
        val selection = replacementSelection ?: activeReplacementSelection() ?: return false
        val selected = replacementOption(selection, option) ?: return false
        if (!transaction.replaceCurrent(selected.words, selection.alternatives)) return false
        replacementSelection = selection
        notifyReplacementOptionsChanged()
        return true
    }

    /** Finalizes the whole selected replacement group after the reel is released. */
    fun releaseReplacement(option: ReplacementOption): Boolean {
        val selection = replacementSelection ?: activeReplacementSelection() ?: return false
        val selected = replacementOption(selection, option) ?: return false
        if (transaction.currentWords != selected.words && !previewReplacement(option)) return false
        finalizeAndClear()
        return true
    }

    /** Restores the transaction's words when a reel drag is cancelled. */
    fun cancelReplacement(): Boolean {
        val selection = replacementSelection ?: return false
        if (transaction.currentWords != selection.sourceWords &&
            !transaction.replaceCurrent(selection.sourceWords, selection.alternatives)
        ) return false
        replacementSelection = null
        notifyReplacementOptionsChanged()
        return true
    }

    /** Resolves delayed split-session output through the coordinator seam. */
    fun poll(atMs: Long): SplitPollOutcome = pollSplitParts(atMs)?.let(SplitPollOutcome::Resolved)
        ?: if (isSplitPending()) SplitPollOutcome.Pending else SplitPollOutcome.Cancelled

    private fun accept(unit: GestureUnit) {
        when (spacingMode()) {
            SpacingMode.MANUAL -> commitCompletedText(unit.topWord())
            SpacingMode.AFTER_SWIPE -> commitCompletedText(unit.topWord() + if (hasFollowingWhitespace()) "" else " ")
            SpacingMode.INFER_SPACES -> acceptInference(unit)
        }
    }

    private fun acceptInference(unit: GestureUnit) {
        if (transaction.units.size == SwipeInferenceTransaction.MAX_GESTURE_UNITS) {
            slideInferenceWindow(unit)
            return
        }
        if (transaction.sourceSpan == null) {
            capitalizeFirstWord = SentenceCapitalization.needsCapitalization(textBeforeCursor())
        }
        check(transaction.append(unit))
        val alternatives = segmenter.rank(transaction.units, previousWords(), dictionary())
        val words = applyPendingCapitalization(alternatives.firstOrNull()?.words ?: unit.topWords())
        replacementSelection = null
        if (!transaction.replaceCurrent(words, alternatives)) finalizeAndClear()
        else notifyReplacementOptionsChanged()
    }

    private fun slideInferenceWindow(nextUnit: GestureUnit) {
        val oldestUnit = transaction.units.first()
        val finalizedAlternatives = segmenter.rank(listOf(oldestUnit), previousWords(), dictionary())
        val rawFinalizedWords = finalizedAlternatives.firstOrNull()?.words ?: oldestUnit.topWords()
        // The transaction's original leading word (if any) is finalized here — apply and
        // consume the capitalization decision now so the retained window's new leading
        // word (mid-sentence from here on) is never capitalized.
        val finalizedWords = applyPendingCapitalization(rawFinalizedWords)
        capitalizeFirstWord = false
        val retainedUnits = transaction.units.drop(1) + nextUnit
        val alternatives = segmenter.rank(retainedUnits, previousWords() + rawFinalizedWords, dictionary())
        val retainedWords = alternatives.firstOrNull()?.words ?: nextUnit.topWords()
        replacementSelection = null
        if (!transaction.slideWindow(
                finalizedWords,
                finalizedAlternatives,
                retainedUnits,
                retainedWords,
                alternatives,
            )
        ) {
            finalizeAndClear()
        } else notifyReplacementOptionsChanged()
    }

    private fun finalizeAndClear() {
        replacementSelection = null
        capitalizeFirstWord = false
        transaction.finalize()
        transaction.clear()
        notifyReplacementOptionsChanged()
    }

    /** Applies the pending sentence-start capitalization to [words]' first entry, if owed. */
    private fun applyPendingCapitalization(words: List<String>): List<String> {
        if (!capitalizeFirstWord || words.isEmpty()) return words
        val capitalized = SentenceCapitalization.capitalizeFirstLetter(words.first())
        return if (capitalized == words.first()) words else listOf(capitalized) + words.drop(1)
    }

    private fun activeReplacementSelection(): ReplacementSelection? =
        transaction.currentWords.takeIf { it.isNotEmpty() }
            ?.takeIf { transaction.alternatives.isNotEmpty() }
            ?.let { words -> ReplacementSelection(words, transaction.alternatives) }

    private fun replacementOption(
        selection: ReplacementSelection,
        option: ReplacementOption,
    ): SegmentationOption? = selection.alternatives.firstOrNull { candidate ->
        ReplacementOption(selection.sourceWords, candidate.words, candidate.score).id == option.id
    }

    private fun notifyReplacementOptionsChanged() = onReplacementOptionsChanged(replacementOptions())

    private data class ReplacementSelection(
        val sourceWords: List<String>,
        val alternatives: List<SegmentationOption>,
    )

    private fun GestureUnit.topWord(): String = topWords().joinToString(separator = "")

    private fun GestureUnit.topWords(): List<String> = candidates.map { candidates -> candidates.first().word.word }

    private fun nextId(): String = "swipe-${nextUnitId++}"
}
