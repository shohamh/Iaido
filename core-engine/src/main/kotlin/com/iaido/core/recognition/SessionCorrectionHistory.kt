package com.iaido.core.recognition

data class SessionWord(
    val id: Int,
    val start: Int,
    val end: Int,
    val original: String,
    val current: String,
    val candidates: List<String>,
    val corrected: Boolean,
)

data class WordReplacement(
    val id: Int,
    val start: Int,
    val end: Int,
    val before: String,
    val after: String,
)

/** Position-indexed metadata for words inserted during one IME session. */
class SessionCorrectionHistory {
    private val entries = mutableListOf<SessionWord>()
    // Composite membership is deliberately session-only. Editor snapshots persist word entries,
    // while a composite is only a transient rendering/lifecycle relationship and is rebuilt by
    // the next edit rather than restoring stale grouping metadata.
    private val reelGroups = mutableListOf<ReelGroup>()
    private var nextId = 0
    private var nextGroupId = 0

    fun record(start: Int, end: Int, original: String, candidates: List<String>): Int {
        require(start >= 0) { "start must be non-negative" }
        require(end >= start) { "end must not precede start" }
        val id = nextId++
        entries += SessionWord(
            id = id,
            start = start,
            end = end,
            original = original,
            current = original,
            candidates = (listOf(original) + candidates).distinct(),
            corrected = false,
        )
        return id
    }

    fun words(): List<SessionWord> = entries.toList()

    fun groups(): List<ReelGroup> = reelGroups.toList()

    /** Updates a word's candidate pool without changing its stable identity or current text. */
    fun updateCandidates(id: Int, candidates: List<String>): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        val entry = entries[index]
        entries[index] = entry.copy(candidates = (listOf(entry.current) + candidates).distinct())
        return true
    }

    /**
     * Updates the currently typed span in place. The first matching start anchor keeps its ID
     * while its end and current text grow or shrink, so every keystroke refreshes one reel.
     */
    fun upsertTyped(start: Int, end: Int, word: String, candidates: List<String>): Int {
        require(start >= 0 && end >= start) { "typed range must be non-negative and ordered" }
        val index = entries.indexOfFirst { it.start == start && it.end <= end }
        if (index < 0) return record(start, end, word, candidates)
        val entry = entries[index]
        val delta = end - entry.end
        entries[index] = entry.copy(
            end = end,
            current = word,
            candidates = (listOf(word) + candidates).distinct(),
            corrected = word != entry.original,
        )
        if (delta != 0) shiftEntriesAfter(index, delta)
        return entry.id
    }

    /** Replaces a source range with one or more independently addressable output words. */
    fun replaceRange(
        start: Int,
        end: Int,
        replacementWords: List<String>,
        candidatesByWord: List<List<String>> = replacementWords.map { emptyList() },
        composite: Boolean = replacementWords.size > 1,
    ): List<Int> {
        require(start >= 0 && end >= start) { "replacement range must be non-negative and ordered" }
        require(replacementWords.isNotEmpty()) { "replacement must contain at least one word" }
        require(candidatesByWord.size == replacementWords.size)

        val affected = entries.filter { it.start < end && it.end > start }
        val anchor = affected.firstOrNull()
        val oldLength = end - start
        entries.removeAll(affected.toSet())
        reelGroups.removeAll { group -> group.sourceStart < end && group.sourceEnd > start }

        val replacementTextLength = replacementWords.joinToString(" ").length
        val delta = replacementTextLength - oldLength
        val insertionIndex = entries.indexOfFirst { it.start >= end }.let { if (it < 0) entries.size else it }
        if (delta != 0) {
            for (index in insertionIndex until entries.size) {
                val entry = entries[index]
                entries[index] = entry.copy(start = entry.start + delta, end = entry.end + delta)
            }
        }

        val ids = replacementWords.mapIndexed { index, word ->
            val id = if (index == 0 && anchor != null) anchor.id else nextId++
            entries += SessionWord(
                id = id,
                start = start + replacementWords.take(index).sumOf { it.length + 1 },
                end = start + replacementWords.take(index).sumOf { it.length + 1 } + word.length,
                original = if (index == 0 && anchor != null) anchor.original else word,
                current = word,
                candidates = (listOf(word) + candidatesByWord[index]).distinct(),
                corrected = anchor != null && index == 0 && word != anchor.original,
            )
            id
        }
        entries.sortBy { it.start }
        if (composite) {
            reelGroups += ReelGroup(
                id = nextGroupId++,
                sourceStart = start,
                sourceEnd = start + replacementTextLength,
                wordIds = ids,
                sourceWords = affected.map { it.current },
                replacementWords = replacementWords,
            )
        }
        return ids
    }

    /** Breaks the transient composite relationship while retaining all independent word entries. */
    fun breakCompositeGroupFor(wordId: Int): ReelGroup? {
        val group = reelGroups.firstOrNull { wordId in it.wordIds } ?: return null
        reelGroups.remove(group)
        return group
    }

    fun snapshot(): SessionCorrectionHistorySnapshot = SessionCorrectionHistorySnapshot(
        nextId = nextId,
        entries = entries.toList(),
    )

    fun restore(snapshot: SessionCorrectionHistorySnapshot) {
        entries.clear()
        entries += snapshot.entries
        nextId = snapshot.nextId
    }

    fun aroundCursor(cursor: Int, maxWords: Int = 3): List<SessionWord> {
        require(maxWords > 0) { "maxWords must be positive" }
        val containing = entries.filter { cursor in it.start until it.end }
        if (containing.isNotEmpty()) return containing.takeLast(maxWords)
        return entries.filter { it.end <= cursor }.takeLast(maxWords)
    }

    fun replace(id: Int, replacement: String): WordReplacement? {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return null
        val entry = entries[index]
        breakCompositeGroupFor(id)
        if (entry.current == replacement) return null
        val edit = WordReplacement(entry.id, entry.start, entry.end, entry.current, replacement)
        val delta = replacement.length - entry.current.length
        entries[index] = entry.copy(
            end = entry.start + replacement.length,
            current = replacement,
            corrected = replacement != entry.original,
        )
        if (delta != 0) {
            shiftEntriesAfter(index, delta)
        }
        return edit
    }

    /** Merges [firstId] and its immediate successor [secondId] into one entry reading [replacement]. */
    fun join(firstId: Int, secondId: Int, replacement: String): WordReplacement? {
        val firstIndex = entries.indexOfFirst { it.id == firstId }
        if (firstIndex < 0) return null
        val secondIndex = entries.indexOfFirst { it.id == secondId }
        if (secondIndex != firstIndex + 1) return null
        val first = entries[firstIndex]
        val second = entries[secondIndex]
        breakCompositeGroupFor(firstId)
        breakCompositeGroupFor(secondId)
        val edit = WordReplacement(first.id, first.start, second.end, first.current, replacement)
        val delta = replacement.length - (second.end - first.start)
        entries[firstIndex] = first.copy(
            end = first.start + replacement.length,
            current = replacement,
            candidates = (listOf(replacement) + first.candidates + second.candidates).distinct(),
            corrected = true,
        )
        entries.removeAt(secondIndex)
        if (delta != 0) {
            shiftEntriesAfter(firstIndex, delta)
        }
        return edit
    }

    fun undo(id: Int): WordReplacement? {
        val entry = entries.firstOrNull { it.id == id } ?: return null
        if (!entry.corrected) return null
        return replace(id, entry.original)
    }

    fun deleteRange(start: Int, end: Int) {
        require(start >= 0) { "start must be non-negative" }
        require(end >= start) { "end must not precede start" }
        if (start == end) return
        val length = end - start
        val removed = entries.filter { it.start < end && it.end > start }.toSet()
        reelGroups.removeAll { group -> group.sourceStart < end && group.sourceEnd > start }
        entries.removeAll(removed)
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.start >= end) {
                entries[index] = entry.copy(start = entry.start - length, end = entry.end - length)
            }
        }
    }

    fun clear() {
        entries.clear()
        reelGroups.clear()
        nextId = 0
        nextGroupId = 0
    }

    private fun shiftEntriesAfter(index: Int, delta: Int) {
        for (later in index + 1 until entries.size) {
            val shifted = entries[later]
            entries[later] = shifted.copy(start = shifted.start + delta, end = shifted.end + delta)
        }
    }
}
