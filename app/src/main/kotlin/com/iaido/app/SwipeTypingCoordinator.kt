package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.GestureUnit
import com.iaido.core.recognition.InferenceSegmenter
import com.iaido.core.recognition.ScoredCandidate
import com.iaido.core.recognition.SplitWordParts
import com.iaido.core.typing.SpacingMode

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
    onFinalizedWords: (HostTextSpan, List<String>) -> Unit = { _, _ -> },
    private val hasFollowingWhitespace: () -> Boolean = { false },
    private val pollSplitParts: (Long) -> SplitWordParts? = { null },
    private val segmenter: InferenceSegmenter = InferenceSegmenter(),
) {
    private val transaction = SwipeInferenceTransaction(
        cursorPosition = cursorPosition,
        replaceHostSpan = replaceHostSpan,
        onFinalized = onFinalizedWords,
    )
    private var nextUnitId = 0L

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

    /** Resolves delayed split-session output through the coordinator seam. */
    fun poll(atMs: Long): SplitWordParts? = pollSplitParts(atMs)

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
        check(transaction.append(unit))
        val alternatives = segmenter.rank(transaction.units, previousWords(), dictionary())
        val words = alternatives.firstOrNull()?.words ?: unit.topWords()
        if (!transaction.replaceCurrent(words, alternatives)) finalizeAndClear()
    }

    private fun slideInferenceWindow(nextUnit: GestureUnit) {
        val oldestUnit = transaction.units.first()
        val finalizedWords = segmenter.rank(listOf(oldestUnit), previousWords(), dictionary())
            .firstOrNull()?.words ?: oldestUnit.topWords()
        val retainedUnits = transaction.units.drop(1) + nextUnit
        val alternatives = segmenter.rank(retainedUnits, previousWords() + finalizedWords, dictionary())
        val retainedWords = alternatives.firstOrNull()?.words ?: nextUnit.topWords()
        if (!transaction.slideWindow(finalizedWords, retainedUnits, retainedWords, alternatives)) {
            finalizeAndClear()
        }
    }

    private fun finalizeAndClear() {
        transaction.finalize()
        transaction.clear()
    }

    private fun GestureUnit.topWord(): String = topWords().joinToString(separator = "")

    private fun GestureUnit.topWords(): List<String> = candidates.map { candidates -> candidates.first().word.word }

    private fun nextId(): String = "swipe-${nextUnitId++}"
}
