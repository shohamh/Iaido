package com.iaido.app

data class EditorSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val offset: Int = 0,
)

data class ManualEditCandidate(
    val start: Int,
    val end: Int,
    val original: String,
    val replacement: String,
)

/** Diffs editor snapshots and separates Iaido edits from external edits. */
class EditorTextChangeDetector {
    private data class ExpectedEdit(val start: Int, val end: Int, val replacement: String)

    private var previous: EditorSnapshot? = null
    private var expected: ExpectedEdit? = null

    fun reset(snapshot: EditorSnapshot) {
        previous = snapshot
        expected = null
    }

    fun expectOwnEdit(start: Int, end: Int, replacement: String) {
        expected = ExpectedEdit(start, end, replacement)
    }

    fun observe(snapshot: EditorSnapshot): ManualEditCandidate? {
        val before = previous
        previous = snapshot
        if (before == null || before.text == snapshot.text) return null

        val expectedEdit = expected
        expected = null
        if (expectedEdit != null && expectedEdit.produced(before, snapshot)) return null

        val edit = singleEdit(before.text, snapshot.text)
        val token = wordRangeAround(before.text, edit.start) ?: return null
        val original = before.text.substring(token.first, token.last + 1)
        val replacementRange = wordRangeAround(snapshot.text, edit.start)
        val replacement = replacementRange?.let { snapshot.text.substring(it.first, it.last + 1) }.orEmpty()
        if (original.isBlank() && replacement.isBlank()) return null
        return ManualEditCandidate(
            start = before.offset + token.first,
            end = before.offset + token.last + 1,
            original = original,
            replacement = replacement,
        )
    }

    /**
     * True when applying this expectation to the previous snapshot's text reproduces the observed text.
     *
     * Comparing the resulting text rather than the literal `(start, end, replacement)` triple matters
     * because [singleEdit] reports the *minimal* diff: a replacement that extends an existing prefix
     * (e.g. replacing `in` with `in to`) is reported as an insertion of `" to"` at the end of the old
     * word, which can never match the edit the IME actually performed. Treating that as an external edit
     * finalized the in-flight inference transaction -- clearing the replacement reel and dropping the
     * separator that the next swipe's word would otherwise be joined with.
     */
    private fun ExpectedEdit.produced(before: EditorSnapshot, after: EditorSnapshot): Boolean {
        if (after.offset != before.offset) return false
        val start = start - before.offset
        val end = end - before.offset
        if (start < 0 || end < start || end > before.text.length) return false
        return buildString(before.text.length + replacement.length) {
            append(before.text, 0, start)
            append(replacement)
            append(before.text, end, before.text.length)
        } == after.text
    }

    private data class TextEdit(val start: Int, val end: Int, val replacement: String)

    private fun singleEdit(before: String, after: String): TextEdit {
        var start = 0
        while (start < before.length && start < after.length && before[start] == after[start]) start++
        var beforeEnd = before.length
        var afterEnd = after.length
        while (beforeEnd > start && afterEnd > start && before[beforeEnd - 1] == after[afterEnd - 1]) {
            beforeEnd--
            afterEnd--
        }
        return TextEdit(start, beforeEnd, after.substring(start, afterEnd))
    }

    private fun wordRangeAround(text: String, index: Int): IntRange? {
        if (text.isEmpty()) return null
        var probe = index.coerceIn(0, text.length)
        if (probe == text.length || !isWordCharacter(text[probe])) probe--
        if (probe < 0 || !isWordCharacter(text[probe])) return null
        var start = probe
        var end = probe + 1
        while (start > 0 && isWordCharacter(text[start - 1])) start--
        while (end < text.length && isWordCharacter(text[end])) end++
        return start until end
    }

    private fun isWordCharacter(character: Char): Boolean = character.isLetterOrDigit() || character == '\''
}
