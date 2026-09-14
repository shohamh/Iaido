package com.iaido.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuggestionStripStateTest {
    @Test
    fun `plain tap is a no-op and release selects a candidate`() {
        val strip = SuggestionStripState(rtl = false)
        strip.update(listOf(SuggestionChip("teh", listOf("teh", "the"))))

        assertEquals(null, strip.tap(0))
        assertEquals("the", strip.release(0, 1))
        assertEquals(1, strip.chips().single().selectedIndex)
        assertEquals("the", strip.release(0, 1))
    }

    @Test
    fun `rtl chips are presented in reverse sentence order`() {
        val strip = SuggestionStripState(rtl = true)
        strip.update(listOf(SuggestionChip("one", listOf("one")), SuggestionChip("two", listOf("two"))))

        assertEquals(listOf("two", "one"), strip.chips().map { it.word })
    }

    @Test
    fun `release with no alternatives is a no-op`() {
        val strip = SuggestionStripState(rtl = false)
        strip.update(listOf(SuggestionChip("word", emptyList())))

        assertEquals(null, strip.release(0, 0))
        assertEquals(0, strip.chips().single().selectedIndex)
    }

    @Test
    fun `corrected state is retained when the strip is updated`() {
        val strip = SuggestionStripState(rtl = false)
        strip.update(listOf(SuggestionChip("teh", listOf("teh", "the"), corrected = true)))

        strip.release(0, 1)
        strip.update(listOf(SuggestionChip("teh", listOf("teh", "the"), corrected = true)))

        assertEquals(1, strip.chips().single().selectedIndex)
        assertEquals(true, strip.chips().single().corrected)
    }

    @Test
    fun `repeated words keep independent reel selections`() {
        val strip = SuggestionStripState(rtl = false)
        strip.update(
            listOf(
                SuggestionChip("same", listOf("same", "first")),
                SuggestionChip("same", listOf("same", "second")),
            ),
        )

        strip.release(1, 1)

        assertEquals(0, strip.chips()[0].selectedIndex)
        assertEquals(1, strip.chips()[1].selectedIndex)
    }
}
