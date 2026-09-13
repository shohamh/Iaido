package com.ninjakeys.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypingControllerTest {
    private fun controller(
        committed: MutableList<String>,
        deleted: MutableList<Int> = mutableListOf(),
        beforeCursor: () -> String = { "" },
    ) = TypingController(committed::add, { deleted += it }, beforeCursor)

    @Test
    fun `tap commits a letter and autocapitalizes at sentence start`() {
        val committed = mutableListOf<String>()

        controller(committed).tap("h", nowMs = 0)

        assertEquals(listOf("H"), committed)
    }

    @Test
    fun `upward letter flick commits its corner number`() {
        val committed = mutableListOf<String>()

        controller(committed).flick("q", FlickDirection.UP)

        assertEquals(listOf("1"), committed)
    }

    @Test
    fun `punctuation flick to space commits punctuation and trailing space`() {
        val committed = mutableListOf<String>()

        controller(committed).punctuationToSpace("?")

        assertEquals(listOf("? "), committed)
    }

    @Test
    fun `double space replaces the first space with a period and space`() {
        val committed = mutableListOf<String>()
        val deleted = mutableListOf<Int>()
        val typing = controller(committed, deleted) { "hello " }

        typing.tap(" ", nowMs = 1000)
        typing.tap(" ", nowMs = 1200)

        assertEquals(listOf(" ", ". "), committed)
        assertEquals(listOf(1), deleted)
    }

    @Test
    fun `long press resolves an English accent`() {
        val committed = mutableListOf<String>()

        controller(committed).longPress("e")

        assertEquals(listOf("é"), committed)
    }

    @Test
    fun `backspace deletes one preceding character`() {
        val committed = mutableListOf<String>()
        val deleted = mutableListOf<Int>()

        controller(committed, deleted).backspace()

        assertEquals(listOf(1), deleted)
    }
}
