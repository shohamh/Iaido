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
    private val onInferenceTransactionFinished: () -> Unit = {},
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
        onRecognizedMultiPathResult(
            parts = parts,
            candidates = candidates,
            touchDownAtMs = parts.indices.map(Int::toLong),
            graceWindowMs = DEFAULT_GRACE_WINDOW_MS,
        )
    }

    fun onRecognizedMultiPathResult(
        parts: List<GesturePath>,
        candidates: List<List<ScoredCandidate>>,
        touchDownAtMs: List<Long>,
        graceWindowMs: Long,
    ) {
        if (
            parts.size !in 1..4 ||
            candidates.size != parts.size ||
            candidates.any { it.isEmpty() } ||
            touchDownAtMs.size != parts.size ||
            touchDownAtMs.zipWithNext().any { (previous, current) -> previous > current } ||
            graceWindowMs < 0L
        ) return onRecognitionFailed()

        if (parts.size == 1) return onRecognizedSingleSwipe(parts.single(), candidates.single())
        accept(
            GestureUnit(
                id = nextId(),
                paths = parts,
                candidates = candidates,
                concurrent = true,
                touchDownAtMs = touchDownAtMs,
                graceWindowMs = graceWindowMs,
            ),
        )
    }

    fun onNonSwipeInput() = finalizeAndClear()

    fun onCursorMoved() = finalizeAndClear()

    fun onExternalEdit() = finalizeAndClear()

    fun onRecognitionFailed() = finalizeAndClear()

    /** Complete structured candidates for the active inference span. */
    fun replacementOptions(): List<ReplacementOption> {
        val selection = replacementSelection ?: activeReplacementSelection() ?: return emptyList()
        return selection.alternatives
            .map { option ->
                val stableId = ReplacementOption(selection.sourceWords, option.words, option.score).id
                ReplacementOption(selection.sourceWords, option.words, option.score, stableId)
            }
            .distinctBy(ReplacementOption::id)
    }

    /** Validates and publishes a pending candidate without changing host text. */
    fun previewReplacement(option: ReplacementOption): Boolean {
        val selection = replacementSelection ?: activeReplacementSelection() ?: return false
        if (replacementOption(selection, option) == null) return false
        replacementSelection = selection
        notifyReplacementOptionsChanged()
        return true
    }

    /** Applies the selected candidate once, then finalizes the whole inference group. */
    fun releaseReplacement(option: ReplacementOption): Boolean {
        val selection = replacementSelection ?: activeReplacementSelection() ?: return false
        val selected = replacementOption(selection, option) ?: return false
        if (transaction.currentWords != selected.words &&
            !transaction.replaceCurrent(selected.words, selection.alternatives)
        ) return false
        finalizeAndClear()
        return true
    }

    /** Discards a pending choice; the editor was never changed by preview. */
    fun cancelReplacement(): Boolean {
        val selection = replacementSelection ?: return false
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
        val alternatives = preserveSingleGestureTopWord(
            unit,
            segmenter.rank(transaction.units, previousWords(), dictionary()),
        )
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
        val hadActiveTransaction = transaction.sourceSpan != null || transaction.units.isNotEmpty()
        replacementSelection = null
        capitalizeFirstWord = false
        transaction.finalize()
        transaction.clear()
        if (hadActiveTransaction) onInferenceTransactionFinished()
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

    /**
     * Inference may split one swipe or join several swipes, but it must not replace a single
     * swipe's best whole-word recognition with another whole word merely because that word is
     * more frequent. That loses the geometric evidence that produced the recognizer result (for
     * example, an exact `hello` path being changed to `help`).
     */
    private fun preserveSingleGestureTopWord(
        unit: GestureUnit,
        alternatives: List<SegmentationOption>,
    ): List<SegmentationOption> {
        val recognizedWords = unit.topWords()
        val first = alternatives.firstOrNull() ?: return alternatives
        if (unit.paths.size != 1 || recognizedWords.size != 1 || first.words.size != 1) return alternatives
        if (first.words == recognizedWords) return alternatives

        return alternatives.sortedWith(
            compareByDescending<SegmentationOption> { it.words == recognizedWords }
                .thenByDescending { it.score },
        )
    }

    private data class ReplacementSelection(
        val sourceWords: List<String>,
        val alternatives: List<SegmentationOption>,
    )

    private fun GestureUnit.topWord(): String = topWords().joinToString(separator = "")

    private fun GestureUnit.topWords(): List<String> = candidates.map { candidates -> candidates.first().word.word }

    private fun nextId(): String = "swipe-${nextUnitId++}"

    private companion object {
        const val DEFAULT_GRACE_WINDOW_MS = 350L
    }
}
