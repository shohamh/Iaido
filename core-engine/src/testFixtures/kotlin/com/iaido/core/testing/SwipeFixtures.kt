package com.iaido.core.testing

import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout

object SwipeFixtures {
    fun pathThrough(layout: KeyboardLayout, word: String, startMs: Long = 0L): GesturePath =
        GesturePath(word.mapIndexed { index, letter ->
            val key = layout.centerOf(letter)
            GesturePoint(key.x, key.y, startMs + index * 10L)
        })

    /** Coordinates match the app's ten-column QWERTY keyboard before screen translation. */
    fun pathThroughQwerty(word: String, keySizePx: Float, startMs: Long = 0L): GesturePath {
        require(word.isNotEmpty()) { "Swipe fixture word cannot be empty" }
        require(word.all(Char::isLetter)) { "Swipe fixture word must contain letters: '$word'" }
        require(keySizePx > 0f) { "keySizePx must be positive" }
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val positions = buildMap {
            rows.forEachIndexed { row, letters ->
                val offset = (10 - letters.length).coerceAtLeast(0) / 2f
                letters.forEachIndexed { index, letter ->
                    put(letter, GesturePoint(
                        x = (index + 0.5f + offset) * keySizePx,
                        y = (row + 0.5f) * keySizePx,
                        timestampMs = 0L,
                    ))
                }
            }
        }
        return GesturePath(word.lowercase().mapIndexed { index, letter ->
            positions.getValue(letter).copy(timestampMs = startMs + index * 10L)
        })
    }

    /** Coordinates match the app's eleven-column Hebrew keyboard before screen translation. */
    fun pathThroughHebrew(word: String, keySizePx: Float, startMs: Long = 0L): GesturePath {
        require(word.isNotEmpty()) { "Swipe fixture word cannot be empty" }
        require(word.all(Char::isLetter)) { "Swipe fixture word must contain letters: '$word'" }
        require(keySizePx > 0f) { "keySizePx must be positive" }
        val rows = listOf("קראטוןםפ", "שדגכעיחלךף", "זסבהנמצתץ")
        val positions = buildMap {
            rows.forEachIndexed { row, letters ->
                val offset = (11 - letters.length).coerceAtLeast(0) / 2f
                letters.forEachIndexed { index, letter ->
                    put(letter, GesturePoint(
                        x = (index + 0.5f + offset) * keySizePx,
                        y = (row + 0.5f) * keySizePx,
                        timestampMs = 0L,
                    ))
                }
            }
        }
        return GesturePath(word.mapIndexed { index, letter ->
            positions.getValue(letter).copy(timestampMs = startMs + index * 10L)
        })
    }
}
