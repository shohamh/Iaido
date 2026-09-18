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
    private var nextId = 0

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
        if (entry.current == replacement) return null
        val edit = WordReplacement(entry.id, entry.start, entry.end, entry.current, replacement)
        val delta = replacement.length - entry.current.length
        entries[index] = entry.copy(
            current = replacement,
            corrected = replacement != entry.original,
        )
        if (delta != 0) {
            for (later in index + 1 until entries.size) {
                val shifted = entries[later]
                entries[later] = shifted.copy(start = shifted.start + delta, end = shifted.end + delta)
            }
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
            for (later in firstIndex + 1 until entries.size) {
                val shifted = entries[later]
                entries[later] = shifted.copy(start = shifted.start + delta, end = shifted.end + delta)
            }
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
        nextId = 0
    }
}
