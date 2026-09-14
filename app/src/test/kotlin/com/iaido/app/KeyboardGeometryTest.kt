package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.layout.KeyPosition
import com.iaido.core.layout.KeyboardLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyboardGeometryTest {
    @Test
    fun `letter rows are centered within the ten-column keyboard`() {
        assertEquals(0f, keyboardRowOffsetUnits(10), 0.001f)
        assertEquals(0.5f, keyboardRowOffsetUnits(9), 0.001f)
        assertEquals(1.5f, keyboardRowOffsetUnits(7), 0.001f)
    }

    @Test
    fun `ime content height reserves the system bottom inset`() {
        val keySizePx = 128f
        val navigationInsetPx = 144f

        assertEquals(512f, keyboardSurfaceHeightPx(keySizePx), 0.001f)
        assertEquals(656f, imeContentHeightPx(keySizePx, navigationInsetPx), 0.001f)
    }

    @Test
    fun `letter hit testing uses the pixel positioned layout`() {
        val layout = KeyboardLayout(
            listOf(
                KeyPosition('q', 64f, 64f),
                KeyPosition('a', 128f, 192f),
            ),
        )

        assertEquals("a", keyAt(128f, 192f, 128f, layout, Language.ENGLISH))
    }
}
