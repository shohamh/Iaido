package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyboardLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class GestureRecognizerTest {

    private val layout = KeyboardLayout.qwertyTestLayout()
    private val recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer())

    private fun pathThrough(vararg letters: Char): GesturePath {
        val points = letters.mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    @Test
    fun `recognizing a swipe through h then i returns hi as the top result`() {
        val dictionary = listOf(
            WordEntry("hi", 1.0),
            WordEntry("no", 1.0),
            WordEntry("bye", 1.0),
        )
        val path = pathThrough('h', 'i')

        val results = recognizer.recognize(path, layout, dictionary)

        assertTrue(results.isNotEmpty(), "expected at least one result")
        assertEquals("hi", results.first().word.word)
    }

    @Test
    fun `recognizing with an empty dictionary returns no results`() {
        val path = pathThrough('h', 'i')

        val results = recognizer.recognize(path, layout, emptyList())

        assertEquals(0, results.size)
    }
}
