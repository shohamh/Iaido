package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SessionWord
import java.text.BreakIterator
import java.util.Locale

/** Pure projection from an observed editor snapshot to one locale-aware sentence. */
internal object SentenceTextModel {
    fun update(
        previous: SentenceStripState?,
        snapshot: EditorSnapshot,
        language: Language,
        history: List<SessionWord>,
        replacementOptions: List<ReplacementOption>,
        textObservationAllowed: Boolean = true,
    ): SentenceStripState {
        if (!textObservationAllowed || snapshot.text.isEmpty()) {
            return emptyState(language, snapshot.selectionStart, snapshot.selectionEnd)
        }

        val locale = locale(language)
        val sentenceRange = sentenceRange(snapshot.text, snapshot.selectionStart - snapshot.offset, locale)
        val sentenceText = snapshot.text.substring(sentenceRange.first, sentenceRange.last + 1)
        val sentenceStart = snapshot.offset + sentenceRange.first
        val spans = wordSpans(sentenceText, locale)
        val identityMapping = translatedPreviousWords(previous, sentenceText)
        val usedIds = mutableSetOf<String>()

        val words = spans.mapIndexed { index, span ->
            val absoluteStart = sentenceStart + span.first
            val absoluteEnd = sentenceStart + span.last + 1
            val text = sentenceText.substring(span.first, span.last + 1)
            val sessionWord = history.firstOrNull { word ->
                word.start == absoluteStart && word.end == absoluteEnd &&
                    word.current.equals(text, ignoreCase = true)
            }
            val priorWord = identityMapping[span.first to (span.last + 1)]
                ?.takeIf { old -> old.text == text }
            val id = when {
                sessionWord != null -> "session:${sessionWord.id}"
                priorWord != null -> priorWord.id
                else -> generatedId(absoluteStart, absoluteEnd, text, index, usedIds)
            }.also(usedIds::add)
            val alternatives = sessionWord?.let { cleanAlternatives(text, it.candidates, locale) }.orEmpty()
            SentenceStripWord(
                id = id,
                text = text,
                start = absoluteStart,
                endExclusive = absoluteEnd,
                above = alternatives.getOrNull(0),
                below = alternatives.getOrNull(1),
            )
        }
        val replacements = resolveReplacements(
            sentenceText = sentenceText,
            sentenceStart = sentenceStart,
            words = words,
            options = replacementOptions,
            selection = snapshot.selectionStart,
            locale = locale,
        )

        return SentenceStripState(
            sentenceText = sentenceText,
            sentenceStart = sentenceStart,
            selectionStart = snapshot.selectionStart,
            selectionEnd = snapshot.selectionEnd,
            words = words,
            replacementOptions = replacements,
            language = language,
        )
    }

    fun emptyState(
        language: Language,
        selectionStart: Int = 0,
        selectionEnd: Int = selectionStart,
    ) = SentenceStripState(
        sentenceText = "",
        sentenceStart = 0,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        words = emptyList(),
        replacementOptions = emptyList(),
        language = language,
    )

    /** Snap an offset returned by layout hit testing to the nearest locale grapheme boundary. */
    fun snapToGraphemeBoundary(text: String, offset: Int, language: Language): Int {
        val bounded = offset.coerceIn(0, text.length)
        val iterator = BreakIterator.getCharacterInstance(locale(language)).apply { setText(text) }
        if (iterator.isBoundary(bounded)) return bounded
        val before = iterator.preceding(bounded).takeUnless { it == BreakIterator.DONE } ?: 0
        val after = iterator.following(bounded).takeUnless { it == BreakIterator.DONE } ?: text.length
        return if (bounded - before <= after - bounded) before else after
    }

    private fun sentenceRange(text: String, selection: Int, locale: Locale): IntRange {
        val cursor = selection.coerceIn(0, text.length)
        val iterator = BreakIterator.getSentenceInstance(locale).apply { setText(text) }
        val start = iterator.preceding((cursor + 1).coerceAtMost(text.length))
            .takeUnless { it == BreakIterator.DONE } ?: 0
        val end = iterator.following(cursor)
            .takeUnless { it == BreakIterator.DONE } ?: text.length
        val boundedStart = start.coerceIn(0, text.length)
        val boundedEnd = end.coerceIn(boundedStart, text.length)
        return boundedStart until boundedEnd.coerceAtLeast(boundedStart + 1)
    }

    private fun wordSpans(text: String, locale: Locale): List<IntRange> {
        val iterator = BreakIterator.getWordInstance(locale).apply { setText(text) }
        val spans = mutableListOf<IntRange>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)
            when {
                token.any(::isLetterOrDigit) -> spans += start until end
                token.isNotEmpty() && token.all(::isCombiningMark) && spans.lastOrNull()?.last == start - 1 -> {
                    spans[spans.lastIndex] = spans.last().first until end
                }
            }
            start = end
            end = iterator.next()
        }
        return spans
    }

    private fun cleanAlternatives(current: String, candidates: List<String>, locale: Locale): List<String> {
        val currentKey = current.lowercase(locale)
        return candidates.asSequence()
            .filter(String::isNotBlank)
            .filter { it.lowercase(locale) != currentKey }
            .distinctBy { it.lowercase(locale) }
            .toList()
    }

    private fun translatedPreviousWords(
        previous: SentenceStripState?,
        currentText: String,
    ): Map<Pair<Int, Int>, SentenceStripWord> {
        if (previous == null || previous.sentenceText.isEmpty()) return emptyMap()
        val oldText = previous.sentenceText
        var commonPrefix = 0
        while (
            commonPrefix < oldText.length && commonPrefix < currentText.length &&
            oldText[commonPrefix] == currentText[commonPrefix]
        ) commonPrefix++
        var oldEnd = oldText.length
        var newEnd = currentText.length
        while (
            oldEnd > commonPrefix && newEnd > commonPrefix &&
            oldText[oldEnd - 1] == currentText[newEnd - 1]
        ) {
            oldEnd--
            newEnd--
        }
        val delta = newEnd - oldEnd
        return buildMap {
            previous.words.forEach { oldWord ->
                val oldStart = oldWord.start - previous.sentenceStart
                val oldWordEnd = oldWord.endExclusive - previous.sentenceStart
                val translated = when {
                    oldWordEnd <= commonPrefix -> oldStart to oldWordEnd
                    oldStart >= oldEnd -> oldStart + delta to oldWordEnd + delta
                    else -> null
                } ?: return@forEach
                if (translated.first >= 0 && translated.second <= currentText.length) {
                    put(translated, oldWord)
                }
            }
        }
    }

    private fun resolveReplacements(
        sentenceText: String,
        sentenceStart: Int,
        words: List<SentenceStripWord>,
        options: List<ReplacementOption>,
        selection: Int,
        locale: Locale,
    ): List<SentenceStripReplacement> = options.distinctBy(ReplacementOption::id).mapNotNull { option ->
        val sources = option.sourceWords
        val matches = words.indices
            .filter { startIndex -> startIndex + sources.size <= words.size }
            .mapNotNull { startIndex ->
                val range = words.subList(startIndex, startIndex + sources.size)
                if (range.map(SentenceStripWord::text).zip(sources)
                        .all { (actual, expected) -> actual.equals(expected, ignoreCase = true) }
                ) startIndex to range else null
            }
        val match = matches.minByOrNull { (_, range) ->
            val center = (range.first().start + range.last().endExclusive) / 2
            kotlin.math.abs(center - selection)
        } ?: return@mapNotNull null
        val range = match.second
        val start = range.first().start
        val end = range.last().endExclusive
        val localStart = start - sentenceStart
        val localEnd = end - sentenceStart
        if (localStart < 0 || localEnd > sentenceText.length) return@mapNotNull null
        SentenceStripReplacement(
            option = option,
            sourceStart = start,
            sourceEndExclusive = end,
            sourceWordIds = range.map(SentenceStripWord::id),
        )
    }

    private fun generatedId(
        start: Int,
        end: Int,
        text: String,
        index: Int,
        usedIds: Set<String>,
    ): String {
        val base = "word:$start:$end:${text.hashCode().toString(16)}:$index"
        if (base !in usedIds) return base
        var suffix = 1
        while ("$base:$suffix" in usedIds) suffix++
        return "$base:$suffix"
    }

    private fun locale(language: Language): Locale = Locale.forLanguageTag(language.localeTag)

    private fun isLetterOrDigit(character: Char): Boolean = character.isLetterOrDigit()

    private fun isCombiningMark(character: Char): Boolean = when (Character.getType(character)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(), -> true
        else -> false
    }
}
