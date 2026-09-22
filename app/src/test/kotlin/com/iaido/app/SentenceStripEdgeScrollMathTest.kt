package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SentenceStripEdgeScrollMathTest {
    @Test
    fun `edge zone penetration controls quadratic acceleration`() {
        assertEquals(24f, SentenceStripEdgeScrollMath.speedCssPxPerSecond(0f))
        assertEquals(219f, SentenceStripEdgeScrollMath.speedCssPxPerSecond(0.5f))
        assertEquals(804f, SentenceStripEdgeScrollMath.speedCssPxPerSecond(1f))
        assertEquals(804f, SentenceStripEdgeScrollMath.speedCssPxPerSecond(2f))
    }

    @Test
    fun `edge speed converts css pixels to device pixels`() {
        assertEquals(438f, SentenceStripEdgeScrollMath.speedPxPerSecond(0.5f, density = 2f))
    }

    @Test
    fun `elapsed edge scroll time is clamped and edge zones keep physical direction in RTL`() {
        assertEquals(0.016f, SentenceStripEdgeScrollMath.frameDeltaSeconds(1_000_000_000L, 1_016_000_000L))
        assertEquals(0.05f, SentenceStripEdgeScrollMath.frameDeltaSeconds(1_000_000_000L, 2_000_000_000L))
        assertEquals(0f, SentenceStripEdgeScrollMath.frameDeltaSeconds(2_000_000_000L, 1_000_000_000L))
        assertEquals(-1f, SentenceStripEdgeScrollMath.scrollSign(EdgeScrollDirection.LEFT, isRtl = false))
        assertEquals(-1f, SentenceStripEdgeScrollMath.scrollSign(EdgeScrollDirection.LEFT, isRtl = true))
        assertEquals(1f, SentenceStripEdgeScrollMath.scrollSign(EdgeScrollDirection.RIGHT, isRtl = true))
    }

    @Test
    fun `edge zones are narrow and penetration grows toward the physical edge`() {
        assertEquals(EdgeScrollTarget(EdgeScrollDirection.LEFT, 1f), SentenceStripEdgeScrollMath.targetAt(0f, 400f, 48f))
        assertEquals(EdgeScrollTarget(EdgeScrollDirection.LEFT, 0.5f), SentenceStripEdgeScrollMath.targetAt(24f, 400f, 48f))
        assertNull(SentenceStripEdgeScrollMath.targetAt(100f, 400f, 48f))
        assertEquals(EdgeScrollTarget(EdgeScrollDirection.RIGHT, 0.5f), SentenceStripEdgeScrollMath.targetAt(376f, 400f, 48f))
        assertEquals(EdgeScrollTarget(EdgeScrollDirection.RIGHT, 1f), SentenceStripEdgeScrollMath.targetAt(400f, 400f, 48f))
    }

    @Test
    fun `edge affordance opacity is transparent at inner edge and restrained outside`() {
        assertEquals(0f, SentenceStripEdgeScrollMath.affordanceAlpha(0f))
        assertEquals(0.16f, SentenceStripEdgeScrollMath.affordanceAlpha(0.5f))
        assertEquals(0.32f, SentenceStripEdgeScrollMath.affordanceAlpha(1f))
    }
}
