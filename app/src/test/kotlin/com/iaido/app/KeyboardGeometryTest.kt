package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.layout.KeyPosition
import com.iaido.core.layout.KeyboardLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyboardGeometryTest {
    @Test
    fun `rendered keyboard and shared geometry use the same ten columns`() {
        assertEquals(KEYBOARD_COLUMN_COUNT, KEYBOARD_LETTER_ROW_COLUMN_COUNT)
    }

    @Test
    fun `letter rows are centered within the ten-column keyboard`() {
        assertEquals(0f, keyboardRowOffsetUnits(10), 0.001f)
        assertEquals(0.5f, keyboardRowOffsetUnits(9), 0.001f)
        assertEquals(1.5f, keyboardRowOffsetUnits(7), 0.001f)
    }

    @Test
    fun `Hebrew lower row fits nine letters between narrow side modifiers`() {
        assertEquals(0.5f, modifierKeyWeight(9, showShift = false), 0.001f)
        assertEquals(1.5f, modifierKeyWeight(7), 0.001f)
        assertEquals(1f, modifierLetterKeyWeight(9, showShift = false), 0.001f)
        assertEquals(10f, modifierKeyWeight(9, false) * 2 + modifierLetterKeyWeight(9, false) * 9, 0.001f)
        assertEquals(100f, modifierRowLetterCenterPx(0, 9, 100f, showShift = false), 0.001f)
        assertEquals(900f, modifierRowLetterCenterPx(8, 9, 100f, showShift = false), 0.001f)
    }

    @Test
    fun `Hebrew letter layout remains in physical left to right row order`() {
        val layout = keyboardLayoutFor(100f, Language.HEBREW, showNumberRow = true)

        assertEquals(150f, layout.centerOf('\u05e7').x, 0.001f)
        assertEquals(850f, layout.centerOf('\u05e4').x, 0.001f)
        assertEquals(modifierRowLetterCenterPx(0, 9, 100f, showShift = false), layout.centerOf('\u05d6').x, 0.001f)
        assertEquals(900f, layout.centerOf('\u05e5').x, 0.001f)
    }

    @Test
    fun `ime content height reserves the system bottom inset`() {
        val keySizePx = 128f
        val navigationInsetPx = 144f

        assertEquals(640f, keyboardSurfaceHeightPx(keySizePx), 0.001f)
        assertEquals(784f, imeContentHeightPx(keySizePx, navigationInsetPx), 0.001f)
    }

    @Test
    fun `number row can be hidden without changing the remaining row geometry`() {
        assertEquals(4, keyboardRowCount(showNumberRow = false))
        assertEquals(512f, keyboardSurfaceHeightPx(128f, rowCount = keyboardRowCount(false)), 0.001f)
        assertEquals(384f, keyboardBottomRowTopPx(128f, rowCount = keyboardRowCount(false)), 0.001f)
    }

    @Test
    fun `all keyboard rows use the full row height for uniform key surfaces`() {
        assertEquals(128f, keyboardKeyHeightPx(128f), 0.001f)
    }

    @Test
    fun `bottom row starts after four full rows with the number row`() {
        assertEquals(512f, keyboardBottomRowTopPx(128f), 0.001f)
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
    fun `bottom row hit testing matches punctuation space and enter keys`() {
        val keySize = 128f
        assertEquals("\uD83C\uDF10", bottomRowKeyAt(0.5f * keySize, keySize, Language.ENGLISH))
        assertEquals(",", bottomRowKeyAt(1.5f * keySize, keySize, Language.ENGLISH))
        assertEquals(" ", bottomRowKeyAt(5f * keySize, keySize, Language.ENGLISH))
        assertEquals(".", bottomRowKeyAt(8.5f * keySize, keySize, Language.ENGLISH))
        assertEquals(ENTER_KEY, bottomRowKeyAt(9.5f * keySize, keySize, Language.ENGLISH))
    }

    @Test
    fun `dedicated number row maps ten equally spaced digits`() {
        val slotWidth = KEYBOARD_LETTER_ROW_COLUMN_COUNT * 100f / 10f

        assertEquals("1", numberRowKeyAt(slotWidth / 2f, 100f))
        assertEquals("0", numberRowKeyAt(slotWidth * 9.5f, 100f))
        assertEquals("1", keyAt(50f, 40f, 100f, KeyboardLayout(listOf(KeyPosition('q', 50f, 150f))), Language.ENGLISH, true))
    }

    @Test
    fun `bottom row includes an enter key at the far right`() {
        val keys = bottomRowKeyWeights(spaceLabel = " ")
        val keySize = 128f
        val rightEdgeX = KEYBOARD_COLUMN_COUNT * keySize - 1f

        assertEquals(ENTER_KEY, keys.last().first)
        assertEquals(ENTER_KEY, bottomRowKeyAt(rightEdgeX, keySize, Language.ENGLISH))
    }

    @Test
    fun `bottom row space is centered with equal weight on either side`() {
        val keys = bottomRowKeyWeights(spaceLabel = "space")
        val leftWeight = keys[0].second + keys[1].second
        val rightWeight = keys[3].second + keys[4].second
        assertEquals(leftWeight, rightWeight, 0.001f)
    }

    @Test
    fun `bottom row weights sum to exactly the shared column count`() {
        // bottomRowKeyAt treats `size` (== widthPx / KEYBOARD_LETTER_ROW_COLUMN_COUNT) as one
        // weight unit and walks cumulative weights up to `columnCount` units. If the weights
        // don't sum to exactly the column count, the hit-testable region falls short of the
        // full screen width and touches near the right edge (where the last key actually
        // renders) silently fail to resolve to any key. This regression previously broke the
        // rightmost key's hit target.
        val totalWeight = bottomRowKeyWeights(spaceLabel = " ").sumOf { it.second.toDouble() }
        assertEquals(KEYBOARD_COLUMN_COUNT.toDouble(), totalWeight, 0.001)
    }

    @Test
    fun `a touch at the far right screen edge resolves to enter`() {
        // Realistic screen-edge coordinate: x at 99% of the full row width
        // (columnCount * keySize == widthPx). The final hit region must include the right edge.
        val keySize = 128f
        val widthPx = KEYBOARD_COLUMN_COUNT.toFloat() * keySize
        val x = 0.99f * widthPx

        assertEquals(ENTER_KEY, bottomRowKeyAt(x, keySize, Language.ENGLISH))
    }

    @Test
    fun `lower letter row hit testing reserves shift and backspace areas`() {
        val size = 100f
        val layout = KeyboardLayout(
            listOf(
                KeyPosition('q', 50f, 150f),
                KeyPosition('a', 150f, 250f),
                KeyPosition('z', 250f, 350f),
                KeyPosition('m', 850f, 350f),
            ),
        )
        assertEquals(SHIFT_KEY, keyAt(0.5f * size, 3.5f * size, size, layout, Language.ENGLISH, true))
        assertEquals("z", keyAt(2.5f * size, 3.5f * size, size, layout, Language.ENGLISH, true))
        assertEquals(BACKSPACE_KEY, keyAt(9.5f * size, 3.5f * size, size, layout, Language.ENGLISH, true))
    }
}
