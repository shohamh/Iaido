package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SentenceStripScrollMathTest {
    @Test
    fun `strip axis transform handles a mirrored RTL row`() {
        val transform = SentenceStripCoordinateMath.fromBounds(
            contentLeft = 20f,
            contentRight = 80f,
            viewportLeft = 520f,
            viewportRight = 460f,
        )

        assertEquals(490f, transform.viewportXFromContent(50f))
        assertEquals(50f, transform.contentXFromViewport(490f))
        assertEquals(80f, transform.contentXFromViewport(460f))
    }

    @Test
    fun `coordinate fallback preserves physical RTL row origin`() {
        assertEquals(
            264f,
            SentenceStripCoordinateMath.contentXFromViewport(
                viewportX = 24f,
                viewportLeftInRoot = 100f,
                rowLeftInRoot = -140f,
            ),
        )
        assertEquals(
            24f,
            SentenceStripCoordinateMath.viewportXFromContent(
                contentX = 264f,
                viewportLeftInRoot = 100f,
                rowLeftInRoot = -140f,
            ),
        )
    }

    @Test
    fun `physical focus offsets map to logical scroll values in both directions`() {
        assertEquals(120f, SentenceStripScrollMath.valueForContentOffset(120f, 300f, isRtl = false))
        assertEquals(180f, SentenceStripScrollMath.valueForContentOffset(120f, 300f, isRtl = true))
        assertEquals(120f, SentenceStripScrollMath.contentOffsetForValue(120f, 300f, isRtl = false))
        assertEquals(120f, SentenceStripScrollMath.contentOffsetForValue(180f, 300f, isRtl = true))
    }

    @Test
    fun `fallback pointer coordinates add the physical content offset in both directions`() {
        assertEquals(170f, SentenceStripScrollMath.contentXFromViewport(50f, 120f, 300f, isRtl = false))
        assertEquals(170f, SentenceStripScrollMath.contentXFromViewport(50f, 180f, 300f, isRtl = true))
    }

    @Test
    fun `overlay coordinates subtract physical scroll offset in both directions`() {
        assertEquals(50f, SentenceStripScrollMath.viewportXFromContent(170f, 120f, 300f, isRtl = false))
        assertEquals(50f, SentenceStripScrollMath.viewportXFromContent(170f, 180f, 300f, isRtl = true))
    }

    @Test
    fun `cursor follow centers within scroll bounds and activates outside comfort band`() {
        assertEquals(0f, SentenceStripScrollMath.centeredContentOffset(40f, 100f, 400f))
        assertEquals(110f, SentenceStripScrollMath.centeredContentOffset(160f, 100f, 400f))
        assertEquals(400f, SentenceStripScrollMath.centeredContentOffset(520f, 100f, 400f))
        assertEquals(false, SentenceStripScrollMath.cursorNeedsFollow(50f, 200f, 28f))
        assertEquals(true, SentenceStripScrollMath.cursorNeedsFollow(20f, 200f, 28f))
        assertEquals(true, SentenceStripScrollMath.cursorNeedsFollow(180f, 200f, 28f))
    }
}
