package com.ninjakeys.core.layout

import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import kotlin.math.max

class KeyboardLayout(val keys: List<KeyPosition>) {

    private val byLetter: Map<Char, KeyPosition> = keys.associateBy { it.letter }

    fun centerOf(letter: Char): KeyPosition =
        byLetter[letter] ?: throw NoSuchElementException("No key for letter '$letter'")

    /**
     * Maps coordinates into a layout-relative space so equivalent keyboard
     * geometries can be compared regardless of pixel size or screen offset.
     */
    fun normalize(path: GesturePath): GesturePath {
        require(keys.isNotEmpty()) { "KeyboardLayout must have at least one key" }
        return GesturePath(
            path.points.map { point ->
                GesturePoint(
                    x = normalizeX(point.x),
                    y = normalizeY(point.y),
                    timestampMs = point.timestampMs,
                )
            }
        )
    }

    fun normalize(position: KeyPosition): KeyPosition =
        position.copy(x = normalizeX(position.x), y = normalizeY(position.y))

    private fun normalizeX(x: Float): Float = (x - minX()) / scale()

    private fun normalizeY(y: Float): Float = (y - minY()) / scale()

    private fun minX(): Float = keys.minOf { it.x }

    private fun minY(): Float = keys.minOf { it.y }

    private fun scale(): Float {
        val width = keys.maxOf { it.x } - minX()
        val height = keys.maxOf { it.y } - minY()
        return max(width, height).coerceAtLeast(1f)
    }

    companion object {
        /**
         * A fixed 3-row QWERTY layout for tests and Stage 1 fixtures.
         * Row y-values increase downward; each row is horizontally offset
         * to loosely match a real QWERTY stagger.
         */
        fun qwertyTestLayout(): KeyboardLayout {
            val row1 = "qwertyuiop"
            val row2 = "asdfghjkl"
            val row3 = "zxcvbnm"
            val keys = mutableListOf<KeyPosition>()
            row1.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f, 0f)) }
            row2.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f + 0.5f, 1f)) }
            row3.forEachIndexed { i, c -> keys.add(KeyPosition(c, i * 1f + 1f, 2f)) }
            return KeyboardLayout(keys)
        }
    }
}
