package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.recognition.ReplacementOption
import com.iaido.core.recognition.SessionWord

/**
 * Finds join candidates across adjacent, already-committed [words]: for each adjacent pair whose
 * concatenated text (case-insensitively) is a valid dictionary entry, offers a [ReplacementOption]
 * joining them into that dictionary word, preserving the first word's capitalization.
 */
internal fun historyJoinCandidates(words: List<SessionWord>, dictionary: List<WordEntry>): List<ReplacementOption> {
    if (words.size < 2) return emptyList()
    val byLowercaseWord = dictionary.associateBy { it.word.lowercase() }
    return words.zipWithNext().mapNotNull { (first, second) ->
        val joined = first.current + second.current
        val entry = byLowercaseWord[joined.lowercase()] ?: return@mapNotNull null
        val replacement = if (first.current.firstOrNull()?.isUpperCase() == true) {
            entry.word.replaceFirstChar { it.uppercase() }
        } else {
            entry.word
        }
        ReplacementOption(listOf(first.current, second.current), listOf(replacement), entry.frequency)
    }
}
