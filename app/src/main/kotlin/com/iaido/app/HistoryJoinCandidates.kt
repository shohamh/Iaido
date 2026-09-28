package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SessionWord
import kotlin.math.sqrt

/**
 * Finds join candidates across adjacent, already-committed [words]: for each adjacent pair whose
 * concatenated text (case-insensitively) is a valid dictionary entry, offers a [ReplacementOption]
 * joining them into that dictionary word, preserving the first word's capitalization.
 */
internal fun historyReplacementCandidates(
    words: List<SessionWord>,
    dictionary: List<WordEntry>,
): List<ReplacementOption> {
    if (words.isEmpty()) return emptyList()
    val byLowercaseWord = dictionary.associateBy { it.word.lowercase() }
    val joins = words.zipWithNext().mapNotNull { (first, second) ->
        val joined = first.current + second.current
        val entry = byLowercaseWord[joined.lowercase()] ?: return@mapNotNull null
        val replacement = if (first.current.firstOrNull()?.isUpperCase() == true) {
            entry.word.replaceFirstChar { it.uppercase() }
        } else {
            entry.word
        }
        ReplacementOption(listOf(first.current, second.current), listOf(replacement), entry.frequency)
    }
    val splits = words.flatMap { word ->
        if (word.current.length < 2) return@flatMap emptyList()
        val sourceFrequency = byLowercaseWord[word.current.lowercase()]?.frequency ?: 0.0
        (1 until word.current.length).mapNotNull { boundary ->
            val left = byLowercaseWord[word.current.substring(0, boundary).lowercase()] ?: return@mapNotNull null
            val right = byLowercaseWord[word.current.substring(boundary).lowercase()] ?: return@mapNotNull null
            val score = sqrt(left.frequency * right.frequency)
            if (score <= sourceFrequency) return@mapNotNull null
            val leftWord = if (word.current.firstOrNull()?.isUpperCase() == true) {
                left.word.replaceFirstChar { it.uppercase() }
            } else {
                left.word
            }
            ReplacementOption(listOf(word.current), listOf(leftWord, right.word), score)
        }
    }.sortedByDescending(ReplacementOption::score)
        .distinctBy(ReplacementOption::id)
        .take(DEFAULT_TYPED_WORD_CANDIDATES)
    return joins + splits
}

internal fun historyJoinCandidates(words: List<SessionWord>, dictionary: List<WordEntry>): List<ReplacementOption> =
    historyReplacementCandidates(words, dictionary).filter { it.sourceWords.size > 1 && it.replacementWords.size == 1 }

internal fun historySplitCandidates(words: List<SessionWord>, dictionary: List<WordEntry>): List<ReplacementOption> =
    historyReplacementCandidates(words, dictionary).filter { it.sourceWords.size == 1 && it.replacementWords.size > 1 }
