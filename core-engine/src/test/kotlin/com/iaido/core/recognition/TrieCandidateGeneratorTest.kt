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

    @Test
    fun `matches capitalized dictionary words against lowercase keyboard layouts`() {
        val candidates = generator.generateCandidates(
            pathThrough('t', 'h', 'e'),
            layout,
            listOf(WordEntry("The", 1.0)),
        )

        assertTrue(candidates.any { it.word == "The" })
    }

    @Test
    fun `keeps the two most recent language dictionary indexes warm`() {
        val english = CountingWordList(WordEntry("hi", 1.0))
        val hebrew = CountingWordList(WordEntry("ani", 1.0))
        val gesture = pathThrough('h', 'i')

        generator.generateCandidates(gesture, layout, english)
        generator.generateCandidates(gesture, layout, hebrew)
        val englishReadsBeforeReuse = english.readCount

        generator.generateCandidates(gesture, layout, english)

        assertTrue(
            english.readCount - englishReadsBeforeReuse == 1,
            "A warm dictionary should only be traversed to filter results, not rebuilt into the trie",
        )
    }

    private class CountingWordList(private vararg val entries: WordEntry) : AbstractList<WordEntry>() {
        var readCount = 0
            private set

        override val size: Int get() = entries.size

        override fun get(index: Int): WordEntry {
            readCount++
            return entries[index]
        }
    }
}
