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

        val edit = singleEdit(before.text, snapshot.text)
        val expectedEdit = expected
        expected = null
        if (expectedEdit != null && appliesTo(expectedEdit, before) == snapshot.text) return null

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
     * Reconstructs the text [edit] would produce when applied to [before], or null if [edit]'s
     * range doesn't fit inside [before]. Comparing the reconstructed string to the observed
     * snapshot (rather than comparing [edit]'s bounds to [singleEdit]'s minimal diff region) is
     * what lets this recognize an expected edit whose replacement shares a prefix or suffix with
     * the text it replaces -- e.g. replacing "in" with "in to" -- since [singleEdit] trims that
     * shared prefix/suffix and reports a smaller region than the one that was actually replaced.
     */
    private fun appliesTo(edit: ExpectedEdit, before: EditorSnapshot): String? {
        val localStart = edit.start - before.offset
        val localEnd = edit.end - before.offset
        if (localStart < 0 || localEnd < localStart || localEnd > before.text.length) return null
        return before.text.substring(0, localStart) + edit.replacement + before.text.substring(localEnd)
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
