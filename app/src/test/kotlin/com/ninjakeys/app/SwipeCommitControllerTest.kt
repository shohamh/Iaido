package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyboardLayout
import com.ninjakeys.core.recognition.GestureRecognizer
import com.ninjakeys.core.recognition.ShapePathScorer
import com.ninjakeys.core.recognition.TrieCandidateGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SwipeCommitControllerTest {
    private val layout = KeyboardLayout.qwertyTestLayout()
    private val recognizer = GestureRecognizer(TrieCandidateGenerator(), ShapePathScorer())

    private fun pathThrough(vararg letters: Char): GesturePath = GesturePath(
        letters.mapIndexed { index, letter ->
            val key = layout.centerOf(letter)
            GesturePoint(key.x, key.y, index * 100L)
        }
    )

    @Test
    fun `commits the top recognized word`() {
        val committed = mutableListOf<String>()
        val controller = SwipeCommitController(
            recognizer,
            listOf(WordEntry("hi", 1.0), WordEntry("no", 1.0)),
            committed::add,
        )

        controller.commit(pathThrough('h', 'i'), layout)

        assertEquals(listOf("hi"), committed)
    }

    @Test
    fun `does not commit when there are no candidates`() {
        val committed = mutableListOf<String>()
        val controller = SwipeCommitController(
            recognizer,
            listOf(WordEntry("bye", 1.0)),
            committed::add,
        )

        controller.commit(pathThrough('h', 'i'), layout)

        assertEquals(emptyList<String>(), committed)
    }

    @Test
    fun `does not commit a short path`() {
        val committed = mutableListOf<String>()
        val controller = SwipeCommitController(
            recognizer,
            listOf(WordEntry("hi", 1.0)),
            committed::add,
        )

        controller.commit(pathThrough('h'), layout)

        assertEquals(emptyList<String>(), committed)
    }

    @Test
    fun `exposes the ranked candidates before committing the winner`() {
        val committed = mutableListOf<String>()
        var candidates: List<String> = emptyList()
        val controller = SwipeCommitController(
            recognizer,
            listOf(WordEntry("hi", 1.0), WordEntry("no", 1.0)),
            committed::add,
            onRecognized = { results -> candidates = results.map { it.word.word } },
        )

        controller.commit(pathThrough('h', 'i'), layout)

        assertEquals(listOf("hi", "no"), candidates)
        assertEquals(listOf("hi"), committed)
    }

    @Test
    fun `applies the runtime ranker before committing`() {
        val committed = mutableListOf<String>()
        val controller = SwipeCommitController(
            recognizer = recognizer,
            dictionary = listOf(WordEntry("hi", 1.0), WordEntry("no", 1.0)),
            commitText = committed::add,
            runtimeRanker = { candidates -> candidates.asReversed() },
        )

        controller.commit(pathThrough('h', 'i'), layout)

        assertEquals(listOf("no"), committed)
    }
}
