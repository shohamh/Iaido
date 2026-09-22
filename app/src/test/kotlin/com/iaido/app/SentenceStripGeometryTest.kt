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
    fun `focus and manual scrolling reach words beyond the first fifteen`() {
        val words = (0 until 24).map { index -> word("word$index", index * 6, index * 6 + 4, 60f) }
        val geometry = SentenceStripGeometry.create(
            words = words,
            gapWidthsPx = List(words.lastIndex) { 10f },
            viewportWidthPx = 180f,
        )

        val focusedIndex = 20
        val focusOffset = geometry.focusScrollOffset(focusedIndex)
        assertEquals(20, geometry.wordAt(focusOffset + geometry.viewportWidthPx / 2f))
        val lastWordOffset = geometry.focusScrollOffset(23)
        assertEquals(geometry.words[23].glyphBounds.centerX - geometry.viewportWidthPx / 2f, lastWordOffset)
        assertEquals(23, geometry.wordAt(lastWordOffset + geometry.viewportWidthPx / 2f))
    }

    @Test
    fun `RTL focus offsets still reach every word in a long generated sentence`() {
        val words = (0 until 30).map { index ->
            word("אב${index}", index * 4, index * 4 + 3, 32f + (index % 4) * 7f)
        }
        val geometry = SentenceStripGeometry.create(
            words = words,
            gapWidthsPx = List(words.lastIndex) { 6f },
            viewportWidthPx = 180f,
            isRtl = true,
        )

        words.indices.forEach { index ->
            val offset = geometry.focusScrollOffset(index)
            val wordBounds = geometry.words[index].hitBounds
            assertTrue(
                wordBounds.right >= offset && wordBounds.left <= offset + geometry.viewportWidthPx,
                "focused RTL word $index should remain visible (offset=$offset)",
            )
        }
    }

    @Test
    fun `cursor content position follows measured caret anchors within the same word`() {
        val measured = SentenceStripMeasuredWord(
            id = "cursor-word",
            start = 10,
            endExclusive = 13,
            laneWidthPx = 40f,
            glyphWidthPx = 30f,
            baselinePx = 10f,
            cursorAnchorsPx = listOf(0f, 9f, 19f, 30f),
        )
        val geometry = SentenceStripGeometry.create(
            words = listOf(measured),
            gapWidthsPx = emptyList(),
            viewportWidthPx = 100f,
        )

        assertEquals(5f, geometry.cursorContentX(0, 10))
        assertEquals(24f, geometry.cursorContentX(0, 12))
        assertEquals(35f, geometry.cursorContentX(0, 99))
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
    fun `RTL deletion follows the physical side into the corresponding logical neighbor`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(word("שלום", 0, 4, 28f), word("עולם", 5, 9, 32f), word("היי", 10, 13, 20f)),
            gapWidthsPx = listOf(4f, 4f),
            viewportWidthPx = 160f,
            isRtl = true,
        )
        val origin = geometry.words[1]

        assertEquals(0..1, geometry.deletionWordRange(1, geometry.words[0].glyphBounds.centerX))
        assertEquals(1..2, geometry.deletionWordRange(1, geometry.words[2].glyphBounds.centerX))
        assertEquals(1, geometry.wordAt(origin.glyphBounds.centerX))
    }

    @Test
    fun `rendered lane bounds can drive RTL deletion hit testing`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(word("one", 0, 3, 20f), word("two", 4, 7, 24f), word("three", 8, 13, 28f)),
            gapWidthsPx = listOf(4f, 4f),
            viewportWidthPx = 120f,
            isRtl = true,
        )
        val rendered = geometry.withRenderedLaneBounds(
            listOf(
                StripRect(84f, 0f, 104f, 26f),
                StripRect(50f, 0f, 74f, 26f),
                StripRect(10f, 0f, 38f, 26f),
            ),
        )

        assertEquals(0..1, rendered.deletionWordRange(1, 100f))
        assertEquals(1..2, rendered.deletionWordRange(1, 20f))
    }

    @Test
    fun `deletion overlay waits for every stable source id before unioning bounds`() {
        val incomplete = unionRenderedWordBounds(
            ids = listOf("new-left", "old-missing", "new-right"),
            boundsById = mapOf(
                "new-left" to StripRect(12f, 0f, 38f, 26f),
                "new-right" to StripRect(64f, 0f, 92f, 26f),
            ),
        )
        assertEquals(null, incomplete)

        assertEquals(
            StripRect(12f, 0f, 92f, 26f),
            unionRenderedWordBounds(
                ids = listOf("new-left", "new-right"),
                boundsById = mapOf(
                    "new-left" to StripRect(12f, 0f, 38f, 26f),
                    "new-right" to StripRect(64f, 0f, 92f, 26f),
                ),
            ),
        )
    }

    @Test
    fun `trailing sentence text participates in RTL row geometry`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(word("one", 0, 3, 20f), word("two", 4, 7, 30f)),
            gapWidthsPx = listOf(8f),
            viewportWidthPx = 100f,
            isRtl = true,
            trailingContentWidthPx = 6f,
        )

        assertEquals(68f, geometry.contentWidthPx)
        assertEquals(68f, geometry.words.first().laneBounds.right)
        assertEquals(6f, geometry.words.last().laneBounds.left)
        assertEquals(0f, geometry.focusRunwayWidthPx)
    }

    @Test
    fun `RTL focus runway stays after the words instead of shifting them right`() {
        val geometry = SentenceStripGeometry.create(
            words = listOf(word("one", 0, 3, 40f), word("two", 4, 7, 40f)),
            gapWidthsPx = listOf(0f),
            viewportWidthPx = 50f,
            isRtl = true,
        )

        assertEquals(25f, geometry.focusRunwayWidthPx)
        assertEquals(84f, geometry.words.first().laneBounds.right)
        assertEquals(0f, geometry.words.last().laneBounds.left)
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
        assertEquals(68f, geometry.focusScrollOffset(2))
        assertEquals(20f, geometry.focusRunwayWidthPx)
        assertEquals(118f, geometry.contentWidthPx)
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
