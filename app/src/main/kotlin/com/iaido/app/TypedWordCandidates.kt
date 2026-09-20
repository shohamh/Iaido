package com.iaido.app

import com.iaido.core.dictionary.WordEntry

/** How many typed-word alternatives a chip offers, besides the typed word itself. */
internal const val DEFAULT_TYPED_WORD_CANDIDATES = 4
private const val MIN_TYPED_WORD_CANDIDATE_LENGTH = 2

/**
 * Correction candidates for a word the user typed: dictionary entries that share its prefix, and
 * entries within one edit of it, most frequent first. `SessionCorrectionHistory.record` always
 * keeps the typed word itself, so it is excluded here; an empty result simply means the strip
 * offers no alternative for that word.
 *
 * Runs on the typing path (once per keystroke), so the scan is deliberately allocation-free: it
 * compares characters case-insensitively instead of lowercasing each entry, gates on length before
 * the edit-distance check, and only allocates for entries that actually match. The dictionaries
 * are 28k (English) and 56k (Hebrew) entries, so a full pass stays in the low milliseconds.
 */
internal fun typedWordCandidates(
    word: String,
    dictionary: List<WordEntry>,
    limit: Int = DEFAULT_TYPED_WORD_CANDIDATES,
): List<String> {
    if (word.length < MIN_TYPED_WORD_CANDIDATE_LENGTH || limit <= 0) return emptyList()
    val prefixMatches = ArrayList<WordEntry>()
    val nearMatches = ArrayList<WordEntry>()
    for (entry in dictionary) {
        val candidate = entry.word
        if (candidate.isEmpty() || candidate.equals(word, ignoreCase = true)) continue
        when {
            candidate.startsWith(word, ignoreCase = true) -> prefixMatches += entry
            isOneEditAwayIgnoringCase(candidate, word) -> nearMatches += entry
        }
    }
    val ranked = prefixMatches.sortedByDescending { it.frequency } +
        nearMatches.sortedByDescending { it.frequency }
    return ranked.take(limit).map { it.word }
}

/** True when [a] and [b] differ by at most one insertion, deletion, or substitution. */
internal fun isOneEditAway(a: String, b: String): Boolean = isOneEditAwayIgnoringCase(a, b)

private fun isOneEditAwayIgnoringCase(a: String, b: String): Boolean {
    val lengthDelta = a.length - b.length
    if (lengthDelta > 1 || lengthDelta < -1) return false
    var indexA = 0
    var indexB = 0
    var edits = 0
    while (indexA < a.length && indexB < b.length) {
        if (a[indexA].equals(b[indexB], ignoreCase = true)) {
            indexA += 1
            indexB += 1
            continue
        }
        edits += 1
        if (edits > 1) return false
        when {
            lengthDelta > 0 -> indexA += 1
            lengthDelta < 0 -> indexB += 1
            else -> {
                indexA += 1
                indexB += 1
            }
        }
    }
    return edits + (a.length - indexA) + (b.length - indexB) <= 1
}
