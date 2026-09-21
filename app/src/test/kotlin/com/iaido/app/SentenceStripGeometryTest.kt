package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentenceStripGeometryTest {
    @Test
    fun `hit bounds extend two pixels into the added four pixel gap`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(word("it", 0, 2, 10f), word("word", 3, 7, 10f)),
            gapWidthsPx = listOf(10f), // Existing separator; the model adds exactly 4 px.
            viewportWidthPx = 100f,
        )

        assertEquals(0f, geometry.words[0].glyphBounds.left)
        assertEquals(10f, geometry.words[0].glyphBounds.right)
        assertEquals(12f, geometry.words[0].hitBounds.right)
        assertEquals(22f, geometry.words[1].hitBounds.left)
        assertEquals(24f, geometry.words[1].glyphBounds.left)
        assertEquals(34f, geometry.words[1].glyphBounds.right)
    }

    @Test
    fun `short word remains independently hittable beside a long word`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(word("it", 0, 2, 12f), word("extraordinarily", 3, 18, 76f)),
            gapWidthsPx = listOf(0f),
            viewportWidthPx = 120f,
        )

        assertEquals(0, geometry.wordAt(6f))
        assertEquals(1, geometry.wordAt(20f))
        assertEquals("it", geometry.words[0].id)
    }

    @Test
    fun `word order and hit testing support left to right and right to left`() {
        val words = listOf(word("one", 0, 3, 20f), word("two", 4, 7, 30f))
        val ltr = SentenceStripGeometry.create(words, listOf(8f), 100f, isRtl = false)
        val rtl = SentenceStripGeometry.create(words, listOf(8f), 100f, isRtl = true)

        assertEquals(0f, ltr.words[0].laneBounds.left)
        assertTrue(rtl.words[0].laneBounds.left > rtl.words[1].laneBounds.left)
        assertEquals(0, rtl.wordAt(rtl.words[0].glyphBounds.centerX))
        assertEquals(1, rtl.wordAt(rtl.words[1].glyphBounds.centerX))
    }

    @Test
    fun `join union covers both lanes and their separating gap`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(word("in", 0, 2, 20f), word("to", 3, 5, 18f)),
            gapWidthsPx = listOf(12f),
            viewportWidthPx = 100f,
        )

        val union = geometry.joinUnion(0, 1)
        assertEquals(0f, union.left)
        assertEquals(54f, union.right)
        assertEquals(27f, union.centerX)
    }

    @Test
    fun `deletion only crosses a word after its midpoint and can pull back`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(
                word("one", 0, 3, 20f),
                word("two", 4, 7, 30f),
                word("three", 8, 13, 24f),
            ),
            gapWidthsPx = listOf(10f, 10f),
            viewportWidthPx = 140f,
        )
        val second = geometry.words[1]

        assertNull(geometry.deletionWordRange(1, second.glyphBounds.centerX - 0.5f))
        assertNull(geometry.deletionWordRange(1, second.hitBounds.right + 0.5f))
        assertEquals(1..1, geometry.deletionWordRange(1, geometry.words[2].hitBounds.left + 0.5f))
        assertEquals(1..2, geometry.deletionWordRange(1, geometry.words[2].glyphBounds.centerX))
        assertEquals(1..1, geometry.deletionWordRange(1, geometry.words[2].hitBounds.left + 0.5f))
        assertNull(geometry.deletionWordRange(1, second.glyphBounds.centerX))
    }

    @Test
    fun `measured cursor anchors map to nearest UTF 16 insertion offset`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(
                SentenceStripMeasuredWord(
                    id = "word",
                    start = 10,
                    endExclusive = 13,
                    laneWidthPx = 30f,
                    glyphWidthPx = 24f,
                    baselinePx = 12f,
                    cursorAnchorsPx = listOf(3f, 8f, 17f, 27f),
                ),
            ),
            gapWidthsPx = emptyList(),
            viewportWidthPx = 80f,
        )

        assertEquals(11, geometry.cursorOffsetAt(0, geometry.words[0].glyphBounds.left + 6f))
        assertEquals(12, geometry.cursorOffsetAt(0, geometry.words[0].glyphBounds.left + 15f))
        assertNull(geometry.cursorOffsetAt(4, 20f))
    }

    @Test
    fun `focus scroll centers a word when possible and clamps at content ends`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(
                word("first", 0, 5, 20f),
                word("middle", 6, 12, 30f),
                word("last", 13, 17, 20f),
            ),
            gapWidthsPx = listOf(10f, 10f),
            viewportWidthPx = 40f,
        )

        assertEquals(0f, geometry.focusScrollOffset(0))
        assertEquals(29f, geometry.focusScrollOffset(1))
        assertEquals(58f, geometry.focusScrollOffset(2))
    }

    private fun word(id: String, start: Int, end: Int, width: Float) =
        SentenceStripMeasuredWord(
            id = id,
            start = start,
            endExclusive = end,
            laneWidthPx = width,
            glyphWidthPx = width,
            baselinePx = 10f,
            cursorAnchorsPx = emptyList(),
        )
}
