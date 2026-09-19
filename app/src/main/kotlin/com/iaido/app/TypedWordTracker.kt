package com.iaido.app

/** A word the user typed, with the span it occupies in the editor. */
internal data class TypedWordSpan(val start: Int, val end: Int, val word: String)

/**
 * Tracks the word currently being typed so it can be corrected like a swiped one.
 *
 * A swipe commits a whole word and the recognizer hands the service candidates, which it records
 * in `SessionCorrectionHistory`. Typed characters arrive one at a time and were recorded nowhere,
 * so a typed word had no addressable span: offering or releasing a correction for it either did
 * nothing at all, or inserted the candidate at the caret instead of replacing the word (the
 * reported "Hiiiiiiii").
 *
 * The tracker closes that gap by holding the open token's start and text until a delimiter, a
 * deletion, or a replacement ends it. It never touches the editor: it hands back the span it
 * believes the token occupies and the caller validates that span before recording it. A span whose
 * end no longer matches where the token ended is dropped rather than recorded, which is what keeps
 * a stale or collapsed span from turning a later correction into an insertion.
 */
internal class TypedWordTracker {
    private val buffer = StringBuilder()
    private var start: Int? = null

    /** Whether a token is currently open. */
    val isTracking: Boolean get() = start != null

    /** The span the open token occupies, or null when nothing is open. */
    fun openSpan(): TypedWordSpan? {
        val begin = start ?: return null
        val word = buffer.toString()
        if (word.isEmpty()) return null
        return TypedWordSpan(begin, begin + word.length, word)
    }

    /**
     * Feeds one committed chunk of text. Single letters extend the open token; anything else - a
     * space, punctuation, a digit, or a whole swiped word - ends it. Returns the token to record
     * when it ends, or null while it stays open.
     */
    fun onCommitted(text: String, cursorBefore: Int, cursorAfter: Int): TypedWordSpan? {
        if (isSingleLetter(text)) {
            if (start == null) start = cursorBefore
            buffer.append(text)
            return null
        }
        return closeAt(cursorBefore)
    }

    /**
     * Feeds a deletion of [count] characters ending at [cursorBefore]. A deletion that reaches the
     * token's first character discards the token; one that only eats its tail shrinks it.
     */
    fun onDeleted(count: Int, cursorBefore: Int) {
        val begin = start ?: return
        val deleteFrom = (cursorBefore - count).coerceAtLeast(0)
        if (deleteFrom <= begin) {
            reset()
            return
        }
        val remaining = (deleteFrom - begin).coerceAtMost(buffer.length)
        buffer.setLength(remaining)
        if (buffer.isEmpty()) reset()
    }

    /**
     * Ends the token, returning it for recording only when it still ends exactly at [cursorAtEnd] -
     * the caret position at the moment the token closed. A mismatch means the editor moved under
     * us, so the token is discarded instead of being recorded at a span that may hold other text.
     */
    fun closeAt(cursorAtEnd: Int): TypedWordSpan? {
        val span = openSpan()
        reset()
        if (span == null) return null
        return span.takeIf { it.word.length >= MIN_TYPED_WORD_LENGTH && it.end == cursorAtEnd }
    }

    fun reset() {
        start = null
        buffer.setLength(0)
    }

    private fun isSingleLetter(text: String) = text.length == 1 && text[0].isLetter()

    companion object {
        /** A single letter is not worth offering corrections for. */
        const val MIN_TYPED_WORD_LENGTH = 2
    }
}