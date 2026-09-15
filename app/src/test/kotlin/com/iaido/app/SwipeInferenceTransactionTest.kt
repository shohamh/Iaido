package com.iaido.app

import com.iaido.core.dictionary.WordEntry
import com.iaido.core.gesture.GesturePath
import com.iaido.core.gesture.GesturePoint
import com.iaido.core.recognition.GestureUnit
import com.iaido.core.recognition.ScoredCandidate
import com.iaido.core.recognition.SegmentationOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SwipeInferenceTransactionTest {
    @Test
    fun `replaces one exact host span and advances its cursor when a segmentation grows`() {
        val editor = FakeEditor("hello ")
        val transaction = SwipeInferenceTransaction(
            cursorPosition = editor::cursor,
            replaceHostSpan = editor::replace,
        )
        transaction.append(unit("joined", "inthe"))

        assertTrue(transaction.replaceCurrent(
            words = listOf("inthe"),
            alternatives = listOf(option(listOf("inthe"))),
        ))
        assertTrue(transaction.replaceCurrent(
            words = listOf("in", "the"),
            alternatives = listOf(option(listOf("in", "the"))),
        ))

        assertEquals("hello in the", editor.text)
        assertEquals(12, editor.cursor())
        assertEquals(HostTextSpan(6, 12), transaction.sourceSpan)
        assertEquals(
            listOf(
                HostTextSpan(6, 6) to "inthe",
                HostTextSpan(6, 11) to "in the",
            ),
            editor.replacements,
        )
    }

    @Test
    fun `finalize records the current words once and clear drops the replaceable run`() {
        val editor = FakeEditor()
        val finalized = mutableListOf<List<String>>()
        val transaction = SwipeInferenceTransaction(
            cursorPosition = editor::cursor,
            replaceHostSpan = editor::replace,
            onFinalized = { _, words -> finalized += words },
        )
        transaction.append(unit("first", "some"))
        assertTrue(transaction.replaceCurrent(listOf("some"), listOf(option(listOf("some")))))

        transaction.finalize()
        transaction.clear()

        assertEquals(listOf(listOf("some")), finalized)
        assertEquals(emptyList<GestureUnit>(), transaction.units)
        assertEquals(emptyList<String>(), transaction.currentWords)
        assertEquals(null, transaction.sourceSpan)
    }

    @Test
    fun `failed host replacement leaves the existing transaction state untouched`() {
        val editor = FakeEditor()
        val transaction = SwipeInferenceTransaction(
            cursorPosition = editor::cursor,
            replaceHostSpan = editor::replace,
        )
        transaction.append(unit("first", "in"))
        assertTrue(transaction.replaceCurrent(listOf("in"), listOf(option(listOf("in")))))
        val previousSpan = transaction.sourceSpan
        val previousWords = transaction.currentWords
        val previousAlternatives = transaction.alternatives

        editor.failReplacements = true
        assertFalse(transaction.replaceCurrent(listOf("in", "the"), listOf(option(listOf("in", "the")))))

        assertEquals("in", editor.text)
        assertEquals(previousSpan, transaction.sourceSpan)
        assertEquals(previousWords, transaction.currentWords)
        assertEquals(previousAlternatives, transaction.alternatives)
    }

    private fun unit(id: String, word: String): GestureUnit = GestureUnit(
        id = id,
        paths = listOf(GesturePath(listOf(GesturePoint(0f, 0f, 0L)))),
        candidates = listOf(listOf(ScoredCandidate(WordEntry(word, 1.0), 1.0))),
        concurrent = false,
    )

    private fun option(words: List<String>) = SegmentationOption(words, 1.0, listOf("source"))

    private class FakeEditor(initialText: String = "") {
        var text = initialText
        private var cursorPosition = initialText.length
        val replacements = mutableListOf<Pair<HostTextSpan, String>>()

        fun cursor(): Int = cursorPosition

        var failReplacements = false

        fun replace(span: HostTextSpan, replacement: String): Boolean {
            if (failReplacements) return false
            replacements += span to replacement
            text = text.replaceRange(span.start, span.end, replacement)
            cursorPosition = span.start + replacement.length
            return true
        }
    }
}
