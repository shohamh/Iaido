package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.recognition.GestureUnit
import com.iaido.core.recognition.InferenceSegmenter
import com.iaido.core.recognition.ScoredCandidate
import com.iaido.core.typing.SpacingMode

/** Coordinates completed recognition units with one editable host-text span. */
class SwipeTypingCoordinator(
    private val spacingMode: () -> SpacingMode,
    private val recognize: (GesturePath, KeyboardLayout) -> List<ScoredCandidate>,
    private val dictionary: () -> List<WordEntry>,
    private val previousWords: () -> List<String>,
    cursorPosition: () -> Int,
    replaceHostSpan: (HostTextSpan, String) -> Unit,
    private val commitCompletedText: (String) -> Unit = { text ->
        val cursor = cursorPosition()
        replaceHostSpan(HostTextSpan(cursor, cursor), text)
    },
    onFinalizedWords: (HostTextSpan, List<String>) -> Unit = { _, _ -> },
    private val segmenter: InferenceSegmenter = InferenceSegmenter(),
) {
    private val transaction = SwipeInferenceTransaction(
        cursorPosition = cursorPosition,
        replaceHostSpan = replaceHostSpan,
        onFinalized = onFinalizedWords,
    )
    private var nextUnitId = 0L
    private var needsBoundaryBeforeNextInference = false

    fun onSingleSwipe(path: GesturePath, layout: KeyboardLayout) {
        onRecognizedSingleSwipe(path, recognize(path, layout))
    }

    fun onRecognizedSingleSwipe(path: GesturePath, candidates: List<ScoredCandidate>) {
        if (candidates.isEmpty()) return
        accept(GestureUnit(nextId(), listOf(path), listOf(candidates), concurrent = false))
    }

    fun onTwoFingerResult(parts: List<GesturePath>, layout: KeyboardLayout) {
        onRecognizedTwoFingerResult(parts, parts.map { path -> recognize(path, layout) })
    }

    fun onRecognizedTwoFingerResult(
        parts: List<GesturePath>,
        candidates: List<List<ScoredCandidate>>,
    ) {
        if (parts.size != 2) return
        if (candidates.size != parts.size || candidates.any { it.isEmpty() }) return
        accept(GestureUnit(nextId(), parts, candidates, concurrent = true))
    }

    fun onNonSwipeInput() = finalizeAndClear()

    fun onCursorMoved() = finalizeAndClear()

    fun onExternalEdit() = finalizeAndClear()

    /** Delayed split-session callers use this as their lifecycle-safe no-op boundary. */
    fun poll(atMs: Long) {
        @Suppress("UNUSED_VARIABLE")
        val ignoredTime = atMs
    }

    private fun accept(unit: GestureUnit) {
        when (spacingMode()) {
            SpacingMode.MANUAL -> commitCompletedText(unit.topWord())
            SpacingMode.AFTER_SWIPE -> commitCompletedText(unit.topWord() + " ")
            SpacingMode.INFER_SPACES -> acceptInference(unit)
        }
    }

    private fun acceptInference(unit: GestureUnit) {
        if (needsBoundaryBeforeNextInference) {
            commitCompletedText(" ")
            needsBoundaryBeforeNextInference = false
        }
        check(transaction.append(unit)) { "Inference run must be cleared after its six-unit bound" }
        val alternatives = segmenter.rank(transaction.units, previousWords(), dictionary())
        val words = alternatives.firstOrNull()?.words ?: unit.topWords()
        transaction.replaceCurrent(words, alternatives)
        if (transaction.units.size == SwipeInferenceTransaction.MAX_GESTURE_UNITS) {
            transaction.finalize()
            transaction.clear()
            needsBoundaryBeforeNextInference = true
        }
    }

    private fun finalizeAndClear() {
        transaction.finalize()
        transaction.clear()
        needsBoundaryBeforeNextInference = false
    }

    private fun GestureUnit.topWord(): String = topWords().joinToString(separator = "")

    private fun GestureUnit.topWords(): List<String> = candidates.map { candidates -> candidates.first().word.word }

    private fun nextId(): String = "swipe-${nextUnitId++}"
}
