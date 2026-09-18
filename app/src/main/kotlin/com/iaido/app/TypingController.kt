package com.iaido.app

enum class FlickDirection { UP, DOWN, LEFT, RIGHT }

class TypingController(
    private val commitText: (String) -> Unit,
    private val deleteSurroundingText: (Int) -> Unit,
    private val textBeforeCursor: () -> String,
    private val doubleSpaceWindowMs: Long = 500L,
) {
    private var lastSpaceTapMs: Long? = null
    private var lastTextAction = LastTextAction.NONE

    fun tap(value: String, nowMs: Long = System.currentTimeMillis()) {
        lastTextAction = LastTextAction.TAP
        if (value == " ") {
            val previousSpace = lastSpaceTapMs
            if (previousSpace != null && nowMs - previousSpace <= doubleSpaceWindowMs &&
                textBeforeCursor().endsWith(" ")
            ) {
                deleteSurroundingText(1)
                commitText(". ")
                lastSpaceTapMs = null
                return
            }
            commitText(value)
            lastSpaceTapMs = nowMs
            return
        }
        lastSpaceTapMs = null
        commitText(capitalizeIfNeeded(value))
    }

    fun commitWord(word: String) {
        markSwipeCommitted()
        commitText(capitalizeIfNeeded(word))
    }

    fun markSwipeCommitted() {
        lastSpaceTapMs = null
        lastTextAction = LastTextAction.SWIPE
    }

    fun markNonSwipeInput() {
        lastSpaceTapMs = null
        lastTextAction = LastTextAction.NONE
    }

    fun flick(letter: String, direction: FlickDirection) {
        lastTextAction = LastTextAction.TAP
        if (direction == FlickDirection.UP) {
            numberFor(letter)?.let(commitText)
        }
    }

    fun punctuationToSpace(punctuation: String) {
        lastTextAction = LastTextAction.TAP
        if (punctuation in setOf(",", ".", "?", "\"", "'", "׳", "״")) {
            commitText(punctuation + " ")
        }
    }

    fun longPress(letter: String) {
        lastTextAction = LastTextAction.TAP
        commitText(accents[letter] ?: letter)
    }

    fun backspace(singleTap: Boolean = true, deleteWord: Boolean = false) {
        lastSpaceTapMs = null
        val count = if (deleteWord || (singleTap && lastTextAction == LastTextAction.SWIPE)) {
            previousWordDeletionCount(textBeforeCursor())
        } else {
            1
        }
        lastTextAction = LastTextAction.NONE
        deleteSurroundingText(count)
    }

    private fun capitalizeIfNeeded(value: String): String =
        SentenceCapitalization.capitalizeIfNeeded(value, textBeforeCursor())

    private fun numberFor(letter: String): String? =
        "qwertyuiop".indexOf(letter.lowercase()).takeIf { it >= 0 }?.let { index ->
            if (index == 9) "0" else (index + 1).toString()
        }

    private companion object {
        val accents = mapOf(
            "a" to "á", "c" to "ç", "e" to "é", "i" to "í",
            "n" to "ñ", "o" to "ó", "u" to "ú",
        )
    }
}

private enum class LastTextAction {
    NONE,
    TAP,
    SWIPE,
}

private fun previousWordDeletionCount(textBeforeCursor: String): Int {
    if (textBeforeCursor.isEmpty()) return 1
    var index = textBeforeCursor.length
    while (index > 0 && textBeforeCursor[index - 1].isWhitespace()) index -= 1
    while (index > 0 && !textBeforeCursor[index - 1].isWhitespace()) index -= 1
    return (textBeforeCursor.length - index).coerceAtLeast(1)
}
