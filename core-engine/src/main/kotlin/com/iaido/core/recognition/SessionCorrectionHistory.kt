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

    fun aroundCursor(cursor: Int, maxWords: Int = 3): List<SessionWord> {
        require(maxWords > 0) { "maxWords must be positive" }
        val containing = entries.filter { cursor in it.start..it.end }
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
