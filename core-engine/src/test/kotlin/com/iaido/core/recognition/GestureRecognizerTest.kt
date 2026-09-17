package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import com.iaido.core.layout.KeyPosition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class GestureRecognizerTest {

    private val layout = KeyboardLayout.qwertyTestLayout()
    private val recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer())

    private val appLayout = KeyboardLayout(
        listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").flatMapIndexed { row, letters ->
            val offset = (10 - letters.length).coerceAtLeast(0) / 2f
            letters.mapIndexed { index, letter ->
                KeyPosition(letter, index + 0.5f + offset, row + 0.5f)
            }
        },
    )

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

    @Test
    fun `context evidence can reorder otherwise shape-ranked candidates`() {
        val recognizer = GestureRecognizer(
            TrieCandidateGenerator(),
            ShapePathScorer(),
            NgramContextScorer(bigrams = mapOf(("to" to "there") to 10.0)),
        )
        val results = recognizer.recognize(
            pathThrough('t', 'h', 'e', 'r', 'e'),
            layout,
            listOf(WordEntry("there", 1.0), WordEntry("three", 1.0)),
            previousWords = listOf("to"),
        )

        assertEquals("there", results.first().word.word)
    }

    private fun appPathThrough(vararg letters: Char): GesturePath {
        val points = letters.mapIndexed { i, c ->
            val key = appLayout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points + points.last())
    }

    @Test
    fun `an exact there path outranks a candidate that skips its leading t`() {
        val path = appPathThrough('t', 'h', 'e', 'r', 'e')
        val results = recognizer.recognize(
            path,
            appLayout,
            listOf(WordEntry("there", 0.00204173794467), WordEntry("here", 0.000933254300797)),
        )

        assertEquals("there", results.first().word.word)
    }

    @Test
    fun `an exact app-layout hello path outranks the shorter help candidate`() {
        val path = appPathThrough('h', 'e', 'l', 'l', 'o')
        val results = recognizer.recognize(
            path,
            appLayout,
            listOf(
                WordEntry("hello", 5.2480746025e-05),
                WordEntry("help", 5.6234132519e-04),
            ),
        )

        assertEquals("hello", results.first().word.word, results.joinToString { "${it.word.word}=${it.score}" })
    }

    @Test
    fun `a path with a coalesced repeated letter still prefers hello`() {
        val path = appPathThrough('h', 'e', 'l', 'o')
        val results = recognizer.recognize(
            path,
            appLayout,
            listOf(
                WordEntry("hello", 5.2480746025e-05),
                WordEntry("help", 5.6234132519e-04),
            ),
        )

        assertEquals("hello", results.first().word.word, results.joinToString { "${it.word.word}=${it.score}" })
    }
}
