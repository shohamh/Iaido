package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyboardLayout
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
}
