package com.iaido.app

import com.iaido.core.dictionary.WordEntry
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
    private val replaceHostSpan: (HostTextSpan, String, String) -> Boolean,
    private val onFinalized: (List<HostTextSpan>, List<String>, List<SegmentationOption>) -> Unit = { _, _, _ -> },
) {
    private val mutableUnits = mutableListOf<GestureUnit>()
    private var renderedText = ""
    private var wordSeparator = " "
    private var trailingSeparator = ""

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

    fun replaceCurrent(
        words: List<String>,
        alternatives: List<SegmentationOption>,
        separator: String = " ",
        trailing: String = "",
    ): Boolean {
        require(words.isNotEmpty()) { "A transaction replacement needs at least one word" }
        val replacement = words.joinToString(separator = separator) + trailing
        val replacedSpan = sourceSpan ?: cursorPosition().let { cursor -> HostTextSpan(cursor, cursor) }
        val expectedText = if (sourceSpan == null) "" else renderedText
        if (!replaceHostSpan(replacedSpan, expectedText, replacement)) return false
        sourceSpan = HostTextSpan(replacedSpan.start, replacedSpan.start + replacement.length)
        renderedText = replacement
        wordSeparator = separator
        trailingSeparator = trailing
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
        separator: String = wordSeparator,
        trailing: String = trailingSeparator,
    ): Boolean {
        require(finalizedWords.isNotEmpty())
        require(retainedUnits.isNotEmpty())
        require(retainedWords.isNotEmpty())
        val replacedSpan = sourceSpan ?: return false
        val finalizedText = finalizedWords.joinToString(separator = separator)
        val retainedText = retainedWords.joinToString(separator = separator)
        val boundary = if (finalizedText.isEmpty() || retainedText.isEmpty()) "" else separator
        val replacement = "$finalizedText$boundary$retainedText$trailing"
        if (!replaceHostSpan(replacedSpan, renderedText, replacement)) return false

        val finalizedSpans = wordSpans(replacedSpan.start, finalizedWords, separator)
        mutableUnits.clear()
        mutableUnits += retainedUnits
        val retainedStart = replacedSpan.start + finalizedText.length + boundary.length
        sourceSpan = HostTextSpan(retainedStart, replacedSpan.start + replacement.length)
        renderedText = "$retainedText$trailing"
        wordSeparator = separator
        trailingSeparator = trailing
        currentWords = retainedWords
        alternatives = retainedAlternatives
        onFinalized(finalizedSpans, finalizedWords, finalizedAlternatives)
        return true
    }

    fun finalize() {
        val span = sourceSpan ?: return
        if (currentWords.isNotEmpty()) {
            onFinalized(wordSpans(span.start, currentWords, wordSeparator), currentWords, alternatives)
        }
    }

    fun clear() {
        mutableUnits.clear()
        currentWords = emptyList()
        sourceSpan = null
        alternatives = emptyList()
        renderedText = ""
        wordSeparator = " "
        trailingSeparator = ""
    }

    private fun wordSpans(start: Int, words: List<String>, separator: String): List<HostTextSpan> {
        var nextStart = start
        return words.map { word ->
            val span = HostTextSpan(nextStart, nextStart + word.length)
            nextStart = span.end + separator.length
            span
        }
    }

    companion object {
        const val MAX_GESTURE_UNITS = 6
    }
}

/** Candidate rows that preserve word positions across equally shaped inferences. */
internal fun inferenceWordCandidates(
    words: List<String>,
    alternatives: List<SegmentationOption>,
    dictionary: List<WordEntry> = emptyList(),
): List<List<String>> = words.indices.map { index ->
    val inferredCandidates = alternatives.asSequence()
        .filter { it.words.size == words.size }
        .map { it.words[index] }
        .distinct()
        .filterNot { candidate -> words[index].length > 1 && candidate.length == 1 }
        .toList()
        .ifEmpty { listOf(words[index]) }
    (inferredCandidates + typedWordCandidates(words[index], dictionary)).distinct()
}
