package com.iaido.core.recognition

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
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

    @Test
    fun `equivalent gestures on differently scaled layouts receive the same score`() {
        val scaledLayout = KeyboardLayout(
            layout.keys.map { key ->
                key.copy(x = key.x * 100f + 50f, y = key.y * 100f + 20f)
            }
        )
        val normalPath = pathThrough('h', 'i')
        val scaledPath = GesturePath(
            normalPath.points.map { point ->
                point.copy(x = point.x * 100f + 50f, y = point.y * 100f + 20f)
            }
        )

        val normalScore = scorer.score(
            normalPath,
            listOf(WordEntry("hi", 1.0)),
            layout,
        ).single().score
        val scaledScore = scorer.score(
            scaledPath,
            listOf(WordEntry("hi", 1.0)),
            scaledLayout,
        ).single().score

        assertEquals(normalScore, scaledScore, 0.0001)
    }

    @Test
    fun `a genuine three-letter cornered path rewards its matching candidate`() {
        val path = pathThrough('b', 'y', 'e')
        val candidates = listOf(WordEntry("bye", 1.0), WordEntry("buy", 1.0))

        val results = scorer.score(path, candidates, layout)

        assertEquals("bye", results.first().word.word)
    }
}
