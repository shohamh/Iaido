package com.ninjakeys.core.layout

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows

class KeyboardLayoutTest {

    @Test
    fun `centerOf returns the position of a known key`() {
        val layout = KeyboardLayout.qwertyTestLayout()

        val position = layout.centerOf('q')

        assertEquals('q', position.letter)
    }

    @Test
    fun `centerOf throws for a letter not in the layout`() {
        val layout = KeyboardLayout(keys = listOf(KeyPosition('a', 0f, 0f)))

        assertThrows<NoSuchElementException> {
            layout.centerOf('z')
        }
    }

    @Test
    fun `qwertyTestLayout places q to the left of w`() {
        val layout = KeyboardLayout.qwertyTestLayout()

        val q = layout.centerOf('q')
        val w = layout.centerOf('w')

        assert(q.x < w.x) { "expected q.x (${q.x}) < w.x (${w.x})" }
    }
}
