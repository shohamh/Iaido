package com.iaido.core.layout

import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
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

    /** Normalized key centers by letter; computed once per layout. */
    val normalizedCenters: Map<Char, KeyPosition> by lazy {
        keys.associate { key -> key.letter to normalize(key) }
    }

    private fun normalizeX(x: Float): Float = (x - minX) / scale

    private fun normalizeY(y: Float): Float = (y - minY) / scale

    private val minX: Float by lazy { keys.minOf { it.x } }

    private val minY: Float by lazy { keys.minOf { it.y } }

    private val scale: Float by lazy {
        val width = keys.maxOf { it.x } - minX
        val height = keys.maxOf { it.y } - minY
        max(width, height).coerceAtLeast(1f)
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

        fun hebrewTestLayout(): KeyboardLayout {
            val rows = listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ")
            val keys = mutableListOf<KeyPosition>()
            rows.forEachIndexed { row, letters ->
                val offset = row * 0.5f
                letters.forEachIndexed { index, letter ->
                    keys.add(KeyPosition(letter, index + offset.toFloat(), row.toFloat()))
                }
            }
            return KeyboardLayout(keys)
        }
    }
}
