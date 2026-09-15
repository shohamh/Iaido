package com.iaido.app

import com.iaido.core.recognition.GestureUnit
import com.iaido.core.recognition.SegmentationOption

data class HostTextSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0) { "Host span start must not be negative" }
        require(end >= start) { "Host span end must not precede start" }
    }
}

/**
 * Owns the one editor span that inference may replace while its swipe run is
 * still active. The app supplies the actual InputConnection mutation.
 */
class SwipeInferenceTransaction(
    private val cursorPosition: () -> Int,
    private val replaceHostSpan: (HostTextSpan, String) -> Boolean,
    private val onFinalized: (HostTextSpan, List<String>, List<SegmentationOption>) -> Unit = { _, _, _ -> },
) {
    private val mutableUnits = mutableListOf<GestureUnit>()

    val units: List<GestureUnit> get() = mutableUnits.toList()
    var currentWords: List<String> = emptyList()
        private set
    var sourceSpan: HostTextSpan? = null
        private set
    var alternatives: List<SegmentationOption> = emptyList()
        private set

    fun append(unit: GestureUnit): Boolean {
        if (mutableUnits.size == MAX_GESTURE_UNITS) return false
        mutableUnits += unit
        return true
    }

    fun replaceCurrent(words: List<String>, alternatives: List<SegmentationOption>): Boolean {
        require(words.isNotEmpty()) { "A transaction replacement needs at least one word" }
        val replacement = words.joinToString(separator = " ")
        val replacedSpan = sourceSpan ?: cursorPosition().let { cursor -> HostTextSpan(cursor, cursor) }
        if (!replaceHostSpan(replacedSpan, replacement)) return false
        sourceSpan = HostTextSpan(replacedSpan.start, replacedSpan.start + replacement.length)
        currentWords = words
        this.alternatives = alternatives
        return true
    }

    /**
     * Commits the oldest unit's resolved words and keeps the latest bounded
     * units replaceable in the same host edit. State changes only after the
     * whole host replacement succeeds.
     */
    fun slideWindow(
        finalizedWords: List<String>,
        finalizedAlternatives: List<SegmentationOption>,
        retainedUnits: List<GestureUnit>,
        retainedWords: List<String>,
        retainedAlternatives: List<SegmentationOption>,
    ): Boolean {
        require(finalizedWords.isNotEmpty())
        require(retainedUnits.isNotEmpty())
        require(retainedWords.isNotEmpty())
        val replacedSpan = sourceSpan ?: return false
        val finalizedText = finalizedWords.joinToString(separator = " ")
        val retainedText = retainedWords.joinToString(separator = " ")
        val replacement = "$finalizedText $retainedText"
        if (!replaceHostSpan(replacedSpan, replacement)) return false

        val finalizedSpan = HostTextSpan(replacedSpan.start, replacedSpan.start + finalizedText.length)
        mutableUnits.clear()
        mutableUnits += retainedUnits
        sourceSpan = HostTextSpan(finalizedSpan.end + 1, replacedSpan.start + replacement.length)
        currentWords = retainedWords
        alternatives = retainedAlternatives
        onFinalized(finalizedSpan, finalizedWords, finalizedAlternatives)
        return true
    }

    fun finalize() {
        val span = sourceSpan ?: return
        if (currentWords.isNotEmpty()) onFinalized(span, currentWords, alternatives)
    }

    fun clear() {
        mutableUnits.clear()
        currentWords = emptyList()
        sourceSpan = null
        alternatives = emptyList()
    }

    companion object {
        const val MAX_GESTURE_UNITS = 6
    }
}

/** Candidate rows that preserve word positions across equally shaped inferences. */
internal fun inferenceWordCandidates(
    words: List<String>,
    alternatives: List<SegmentationOption>,
): List<List<String>> = words.indices.map { index ->
    alternatives.asSequence()
        .filter { it.words.size == words.size }
        .map { it.words[index] }
        .distinct()
        .toList()
        .ifEmpty { listOf(words[index]) }
}
