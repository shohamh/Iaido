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
    fun `all keyboard rows use the full row height for uniform key surfaces`() {
        assertEquals(128f, keyboardKeyHeightPx(128f), 0.001f)
    }

    @Test
    fun `bottom row starts after three full rows and keeps the same height`() {
        assertEquals(384f, keyboardBottomRowTopPx(128f), 0.001f)
        assertEquals(128f, keyboardBottomRowHeightPx(128f), 0.001f)
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

    @Test
    fun `bottom row hit testing includes the settings button`() {
        // Weights: globe 0.75, settings 0.75, space 6, backspace 1.5 (total 9 units).
        assertEquals(SETTINGS_KEY, bottomRowKeyAt(1.0f * 128f, 128f, Language.ENGLISH))
        assertEquals(" ", bottomRowKeyAt(4.5f * 128f, 128f, Language.ENGLISH))
        assertEquals("⌫", bottomRowKeyAt(8.5f * 128f, 128f, Language.ENGLISH))
    }

    @Test
    fun `bottom row space is centered with equal weight on either side`() {
        val keys = listOf(
            GLOBE_KEY_TEST to 0.75f,
            SETTINGS_KEY to 0.75f,
            "space" to 6f,
            BACKSPACE_KEY_TEST to 1.5f,
        )
        val leftWeight = keys[0].second + keys[1].second
        val rightWeight = keys[3].second
        assertEquals(leftWeight, rightWeight, 0.001f)
    }
}

private const val GLOBE_KEY_TEST = "🌐"
private const val BACKSPACE_KEY_TEST = "⌫"
