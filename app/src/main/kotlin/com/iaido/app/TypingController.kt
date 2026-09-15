package com.iaido.app

enum class FlickDirection { UP, DOWN, LEFT, RIGHT }

class TypingController(
    private val commitText: (String) -> Unit,
    private val deleteSurroundingText: (Int) -> Unit,
    private val textBeforeCursor: () -> String,
    private val doubleSpaceWindowMs: Long = 500L,
) {
    private var lastSpaceTapMs: Long? = null

    fun tap(value: String, nowMs: Long = System.currentTimeMillis()) {
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
        lastSpaceTapMs = null
        commitText(capitalizeIfNeeded(word))
    }

    fun flick(letter: String, direction: FlickDirection) {
        if (direction == FlickDirection.UP) {
            numberFor(letter)?.let(commitText)
        }
    }

    fun punctuationToSpace(punctuation: String) {
        if (punctuation in setOf(",", ".", "?", "\"", "'", "׳", "״")) {
            commitText(punctuation + " ")
        }
    }

    fun longPress(letter: String) {
        commitText(accents[letter] ?: letter)
    }

    fun backspace() {
        lastSpaceTapMs = null
        deleteSurroundingText(1)
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
