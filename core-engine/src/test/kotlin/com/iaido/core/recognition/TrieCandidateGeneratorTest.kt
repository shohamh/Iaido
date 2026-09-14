package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse

class TrieCandidateGeneratorTest {

    private val layout = KeyboardLayout.qwertyTestLayout()
    private val generator = TrieCandidateGenerator()

    private fun pathThrough(vararg letters: Char): GesturePath {
        val points = letters.mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    @Test
    fun `includes a word whose letters lie on the path`() {
        val dictionary = listOf(WordEntry("hi", 1.0), WordEntry("bye", 1.0))
        val path = pathThrough('h', 'i')

        val candidates = generator.generateCandidates(path, layout, dictionary)

        assertTrue(candidates.any { it.word == "hi" })
    }

    @Test
    fun `excludes a word whose letters are far from the path`() {
        val dictionary = listOf(WordEntry("hi", 1.0), WordEntry("bye", 1.0))
        val path = pathThrough('h', 'i')

        val candidates = generator.generateCandidates(path, layout, dictionary)

        assertFalse(candidates.any { it.word == "bye" })
    }

    @Test
    fun `candidate matching is independent of layout scale and offset`() {
        val scaledLayout = KeyboardLayout(
            layout.keys.map { key ->
                key.copy(x = key.x * 100f + 50f, y = key.y * 100f + 20f)
            }
        )
        val scaledPath = GesturePath(
            pathThrough('h', 'i').points.map { point ->
                point.copy(x = point.x * 100f + 50f, y = point.y * 100f + 20f)
            }
        )

        val candidates = generator.generateCandidates(
            scaledPath,
            scaledLayout,
            listOf(WordEntry("hi", 1.0)),
        )

        assertTrue(candidates.any { it.word == "hi" })
    }
}
