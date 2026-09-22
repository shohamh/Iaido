package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CursorWordFocusMathTest {
    private data class Span(val start: Int, val end: Int)

    @Test
    fun `cursor focus chooses the nearer word when selection is in whitespace`() {
        val words = listOf(Span(0, 3), Span(8, 11))

        assertEquals(
            1,
            cursorFocusedWordIndex(words, 7, Span::start, Span::end),
        )
    }

    @Test
    fun `previous focus breaks an exact whitespace distance tie`() {
        val words = listOf(Span(0, 3), Span(7, 10))

        assertEquals(0, cursorFocusedWordIndex(words, 5, Span::start, Span::end, previousFocusedIndex = 0))
        assertEquals(1, cursorFocusedWordIndex(words, 5, Span::start, Span::end, previousFocusedIndex = 1))
        assertEquals(0, cursorFocusedWordIndex(words, 5, Span::start, Span::end))
    }

    @Test
    fun `empty sentence has no focused word`() {
        assertNull(cursorFocusedWordIndex(emptyList<Span>(), 0, Span::start, Span::end))
    }
}
