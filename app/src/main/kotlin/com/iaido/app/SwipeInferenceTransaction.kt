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
    private val replaceHostSpan: (HostTextSpan, String) -> Unit,
    private val onFinalized: (HostTextSpan, List<String>) -> Unit = { _, _ -> },
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

    fun replaceCurrent(words: List<String>, alternatives: List<SegmentationOption>) {
        require(words.isNotEmpty()) { "A transaction replacement needs at least one word" }
        val replacement = words.joinToString(separator = " ")
        val replacedSpan = sourceSpan ?: cursorPosition().let { cursor -> HostTextSpan(cursor, cursor) }
        replaceHostSpan(replacedSpan, replacement)
        sourceSpan = HostTextSpan(replacedSpan.start, replacedSpan.start + replacement.length)
        currentWords = words
        this.alternatives = alternatives
    }

    fun finalize() {
        val span = sourceSpan ?: return
        if (currentWords.isNotEmpty()) onFinalized(span, currentWords)
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
