package com.iaido.app

import androidx.compose.ui.unit.LayoutDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SentenceStripLayoutMathTest {
    @Test
    fun `measured text uses the same bidi direction as its rendered strip`() {
        assertEquals(LayoutDirection.Ltr, SentenceStripLayoutMath.textDirection(isRtl = false))
        assertEquals(LayoutDirection.Rtl, SentenceStripLayoutMath.textDirection(isRtl = true))
    }
}
