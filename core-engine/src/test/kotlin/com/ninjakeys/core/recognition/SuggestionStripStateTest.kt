package com.ninjakeys.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuggestionStripStateTest {
    @Test
    fun `plain tap is a no-op and release selects a candidate`() {
        val strip = SuggestionStripState(rtl = false)
        strip.update(listOf(SuggestionChip("teh", listOf("teh", "the"))))

        assertEquals(null, strip.tap(0))
        assertEquals("the", strip.release(0, 1))
        assertEquals(1, strip.release(0, 1)?.let { 1 })
    }

    @Test
    fun `rtl chips are presented in reverse sentence order`() {
        val strip = SuggestionStripState(rtl = true)
        strip.update(listOf(SuggestionChip("one", listOf("one")), SuggestionChip("two", listOf("two"))))

        assertEquals(listOf("two", "one"), strip.chips().map { it.word })
    }
}
