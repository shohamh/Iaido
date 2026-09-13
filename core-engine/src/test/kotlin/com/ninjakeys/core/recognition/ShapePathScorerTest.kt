package com.ninjakeys.core.recognition

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyboardLayout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class ShapePathScorerTest {

    private val layout = KeyboardLayout.qwertyTestLayout()
    private val scorer = ShapePathScorer()

    private fun pathThrough(vararg letters: Char): GesturePath {
        val points = letters.mapIndexed { i, c ->
            val key = layout.centerOf(c)
            GesturePoint(key.x, key.y, i * 100L)
        }
        return GesturePath(points)
    }

    @Test
    fun `a path drawn through hi's own keys scores hi above an unrelated word`() {
        val path = pathThrough('h', 'i')
        val candidates = listOf(WordEntry("hi", 1.0), WordEntry("no", 1.0))

        val results = scorer.score(path, candidates, layout)

        val hiScore = results.first { it.word.word == "hi" }.score
        val noScore = results.first { it.word.word == "no" }.score
        assertTrue(hiScore > noScore, "expected hi ($hiScore) > no ($noScore)")
    }

    @Test
    fun `results are sorted descending by score`() {
        val path = pathThrough('h', 'i')
        val candidates = listOf(WordEntry("no", 1.0), WordEntry("hi", 1.0))

        val results = scorer.score(path, candidates, layout)

        assertEquals("hi", results.first().word.word)
    }

    @Test
    fun `scoring an empty candidate list returns an empty result`() {
        val path = pathThrough('h', 'i')

        val results = scorer.score(path, emptyList(), layout)

        assertEquals(0, results.size)
    }
}
