package com.iaido.app

internal enum class SentenceAlternativeSide { ABOVE, BELOW }

internal sealed interface SentenceStripPreview {
    val sentenceText: String
}

internal data class SentenceWordPreview(
    val wordIndex: Int,
    val side: SentenceAlternativeSide,
    val replacement: String,
    override val sentenceText: String,
) : SentenceStripPreview

internal data class SentenceReplacementPreview(
    val replacement: SentenceStripReplacement,
    val sourceWordIndices: List<Int>,
    val side: SentenceAlternativeSide,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    override val sentenceText: String,
) : SentenceStripPreview {
    val optionId: String get() = replacement.option.id
    val replacementWords: List<String> get() = replacement.option.replacementWords
    val replacementText: String get() = replacementWords.joinToString(" ")
    val isJoin: Boolean get() = sourceWordIndices.size > 1 && replacementWords.size == 1
    val isSplit: Boolean get() = sourceWordIndices.size == 1 && replacementWords.size > 1
}

internal data class SentenceDeletionPreview(
    val wordRange: IntRange,
    val sourceWordIds: List<String>,
    val sourceStart: Int,
    val sourceEndExclusive: Int,
    val deletedText: String,
    override val sentenceText: String,
) : SentenceStripPreview

internal data class SentenceWordAlternativeOverride(
    val selectedText: String,
    val displacedText: String,
    val side: SentenceAlternativeSide,
) {
    fun apply(word: SentenceStripWord): SentenceStripWord = when (side) {
        SentenceAlternativeSide.ABOVE -> word.copy(above = displacedText)
        SentenceAlternativeSide.BELOW -> word.copy(below = displacedText)
    }
}

/** Pure preview projection; no editor action is reachable from this code. */
internal object SentenceStripPreviewMath {
    fun alternative(
        state: SentenceStripState,
        wordIndex: Int,
        side: SentenceAlternativeSide,
    ): SentenceStripPreview? {
        val word = state.words.getOrNull(wordIndex) ?: return null
        val candidate = when (side) {
            SentenceAlternativeSide.ABOVE -> word.above
            SentenceAlternativeSide.BELOW -> word.below
        }?.takeIf(String::isNotBlank) ?: return null
        state.replacementOptions.firstOrNull { replacement ->
            word.id in replacement.sourceWordIds &&
                replacement.option.replacementWords.joinToString(" ").equals(candidate, ignoreCase = true)
        }?.let { replacement ->
            replacement(state, replacement, wordIndex, side)?.let { return it }
        }

        val localStart = (word.start - state.sentenceStart).coerceIn(0, state.sentenceText.length)
        val localEnd = (word.endExclusive - state.sentenceStart).coerceIn(localStart, state.sentenceText.length)
        return SentenceWordPreview(
            wordIndex = wordIndex,
            side = side,
            replacement = candidate,
            sentenceText = state.sentenceText.replaceRange(localStart, localEnd, candidate),
        )
    }

    fun replacement(
        state: SentenceStripState,
        replacement: SentenceStripReplacement,
        triggerWordIndex: Int,
        side: SentenceAlternativeSide = SentenceAlternativeSide.ABOVE,
    ): SentenceReplacementPreview? {
        val sourceIndices = state.words.indices.filter { index ->
            val word = state.words[index]
            word.id in replacement.sourceWordIds ||
                word.start >= replacement.sourceStart && word.endExclusive <= replacement.sourceEndExclusive
        }
        if (triggerWordIndex !in sourceIndices || sourceIndices.isEmpty()) return null
        val outputWords = replacement.option.replacementWords
        if (outputWords.joinToString(" ").equals(
                replacement.option.sourceWords.joinToString(" "),
                ignoreCase = true,
            )
        ) return null
        val localStart = (replacement.sourceStart - state.sentenceStart).coerceIn(0, state.sentenceText.length)
        val localEnd = (replacement.sourceEndExclusive - state.sentenceStart)
            .coerceIn(localStart, state.sentenceText.length)
        return SentenceReplacementPreview(
            replacement = replacement,
            sourceWordIndices = sourceIndices,
            side = side,
            sourceStart = replacement.sourceStart,
            sourceEndExclusive = replacement.sourceEndExclusive,
            sentenceText = state.sentenceText.replaceRange(localStart, localEnd, outputWords.joinToString(" ")),
        )
    }

    fun deletion(state: SentenceStripState, wordRange: IntRange): SentenceDeletionPreview? {
        if (wordRange.isEmpty() || wordRange.first < 0 || wordRange.last !in state.words.indices) return null
        val first = state.words[wordRange.first]
        val last = state.words[wordRange.last]
        var start = (first.start - state.sentenceStart).coerceIn(0, state.sentenceText.length)
        var end = (last.endExclusive - state.sentenceStart).coerceIn(start, state.sentenceText.length)
        if (end < state.sentenceText.length && state.sentenceText[end].isWhitespace()) {
            while (end < state.sentenceText.length && state.sentenceText[end].isWhitespace()) end++
        } else if (start > 0 && state.sentenceText[start - 1].isWhitespace()) {
            while (start > 0 && state.sentenceText[start - 1].isWhitespace()) start--
        }
        val deletedText = state.sentenceText.substring(start, end)
        return SentenceDeletionPreview(
            wordRange = wordRange,
            sourceWordIds = state.words.slice(wordRange).map(SentenceStripWord::id),
            sourceStart = state.sentenceStart + start,
            sourceEndExclusive = state.sentenceStart + end,
            deletedText = deletedText,
            sentenceText = state.sentenceText.replaceRange(start, end, ""),
        )
    }
}
