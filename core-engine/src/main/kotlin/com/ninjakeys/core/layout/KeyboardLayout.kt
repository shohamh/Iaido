package com.ninjakeys.core.layout

class KeyboardLayout(val keys: List<KeyPosition>) {

    private val byLetter: Map<Char, KeyPosition> = keys.associateBy { it.letter }

    fun centerOf(letter: Char): KeyPosition =
        byLetter[letter] ?: throw NoSuchElementException("No key for letter '$letter'")

    companion object {
        /**
         * A fixed 3-row QWERTY layout for tests and Stage 1 fixtures.
         * Row y-values increase downward; each row is horizontally offset
         * to loosely match a real QWERTY stagger.
         */
        fun qwertyTestLayout(): KeyboardLayout {
            val row1 = "qwertyuiop"
            val row2 = "asdfghjkl"
            val row3 = "zxcvbnm"
            val keys = mutableListOf<KeyPosition>()
            row1.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f, 0f)) }
            row2.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f + 0.5f, 1f)) }
            row3.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f + 1f, 2f)) }
            return KeyboardLayout(keys)
        }
    }
}
