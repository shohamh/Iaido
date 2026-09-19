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
        // Weights: globe 1, settings 1, space 7, backspace 2 (total 11 units, matching
        // KEYBOARD_LETTER_ROW_COLUMN_COUNT).
        assertEquals(SETTINGS_KEY, bottomRowKeyAt(1.0f * 128f, 128f, Language.ENGLISH))
        assertEquals(" ", bottomRowKeyAt(5.5f * 128f, 128f, Language.ENGLISH))
        assertEquals("⌫", bottomRowKeyAt(10.5f * 128f, 128f, Language.ENGLISH))
    }

    @Test
    fun `bottom row space is centered with equal weight on either side`() {
        val keys = bottomRowKeyWeights(spaceLabel = "space")
        val leftWeight = keys[0].second + keys[1].second
        val rightWeight = keys[3].second
        assertEquals(leftWeight, rightWeight, 0.001f)
    }

    @Test
    fun `bottom row weights sum to exactly the shared column count`() {
        // bottomRowKeyAt treats `size` (== widthPx / KEYBOARD_LETTER_ROW_COLUMN_COUNT) as one
        // weight unit and walks cumulative weights up to `columnCount` units. If the weights
        // don't sum to exactly the column count, the hit-testable region falls short of the
        // full screen width and touches near the right edge (where the last key actually
        // renders) silently fail to resolve to any key. This regression previously broke the
        // backspace key entirely.
        val totalWeight = bottomRowKeyWeights(spaceLabel = " ").sumOf { it.second.toDouble() }
        assertEquals(KEYBOARD_LETTER_ROW_COLUMN_COUNT.toDouble(), totalWeight, 0.001)
    }

    @Test
    fun `a touch at the far right screen edge resolves to backspace`() {
        // Realistic screen-edge coordinate: x at 99% of the full row width
        // (columnCount * keySize == widthPx). This is exactly the scenario that silently
        // broke when the row's weights summed to less than KEYBOARD_LETTER_ROW_COLUMN_COUNT --
        // touches in the rightmost portion of the row fell past every cumulative threshold and
        // resolved to null instead of the backspace key.
        val keySize = 128f
        val widthPx = KEYBOARD_LETTER_ROW_COLUMN_COUNT.toFloat() * keySize
        val x = 0.99f * widthPx

        assertEquals(BACKSPACE_KEY_TEST, bottomRowKeyAt(x, keySize, Language.ENGLISH))
    }
}

private const val BACKSPACE_KEY_TEST = "⌫"
