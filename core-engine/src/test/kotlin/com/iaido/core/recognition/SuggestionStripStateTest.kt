package com.iaido.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SuggestionStripStateTest {
    @Test
    fun `structured replacement release preserves a two word source and one word replacement`() {
        val strip = SuggestionStripState(rtl = false)
        val joined = ReplacementOption(
            sourceWords = listOf("in", "the"),
            replacementWords = listOf("inthe"),
            score = 0.8,
        )
        strip.updateReplacementOptions(
            listOf(
                ReplacementOption(listOf("in", "the"), listOf("in", "the"), 1.0),
                joined,
            ),
        )

        assertEquals(joined, strip.previewReplacement(1))
        assertEquals(joined, strip.releaseReplacement(1))
        assertEquals(2, strip.replacementOptions().size)
        assertEquals(listOf("in", "the"), strip.selectedReplacement()?.sourceWords)
        assertEquals(listOf("inthe"), strip.selectedReplacement()?.replacementWords)
    }

    @Test
    fun `replacement cancellation discards preview without changing selected replacement`() {
        val strip = SuggestionStripState(rtl = false)
        val original = ReplacementOption(listOf("inthe"), listOf("inthe"), 1.0)
        val split = ReplacementOption(listOf("inthe"), listOf("in", "the"), 0.9)
        strip.updateReplacementOptions(listOf(original, split))

        assertEquals(split, strip.previewReplacement(1))
        strip.cancelReplacement()

        assertEquals(null, strip.selectedReplacement())
        assertEquals(original, strip.displayedReplacement())
    }

    @Test
    fun `rtl replacement options retain logical source and replacement word order`() {
        val strip = SuggestionStripState(rtl = true)
        val option = ReplacementOption(
            sourceWords = listOf("one", "two"),
            replacementWords = listOf("first", "second", "third"),
            score = 1.0,
        )

        strip.updateReplacementOptions(listOf(option))

        assertEquals(listOf("one", "two"), strip.replacementOptions().single().sourceWords)
        assertEquals(listOf("first", "second", "third"), strip.replacementOptions().single().replacementWords)
    }

    @Test
    fun `replacement option ids stay distinct for different structured word boundaries`() {
        val first = ReplacementOption(listOf("a\\u001fb", "c"), listOf("word"), 1.0)
        val second = ReplacementOption(listOf("a", "b\\u001fc"), listOf("word"), 1.0)

        assert(first.id != second.id)
    }

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
