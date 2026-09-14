package com.ninjakeys.core.recognition

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SessionCorrectionHistoryTest {
    @Test
    fun `replacement preserves original text and shifts later ranges`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 3, "teh", listOf("teh", "the"))
        val second = history.record(4, 9, "world", listOf("world"))

        val edit = history.replace(first, "thee")

        assertEquals(0, edit?.start)
        assertEquals(3, edit?.end)
        assertEquals("teh", edit?.before)
        assertEquals("thee", edit?.after)
        assertEquals("teh", history.words().single { it.id == first }.original)
        assertEquals("thee", history.words().single { it.id == first }.current)
        assertEquals(5, history.words().single { it.id == second }.start)
        assertEquals(10, history.words().single { it.id == second }.end)
    }

    @Test
    fun `undo returns only an autocorrected word to its original`() {
        val history = SessionCorrectionHistory()
        val untouched = history.record(0, 4, "word", listOf("word"))
        val corrected = history.record(5, 8, "teh", listOf("teh", "the"))
        history.replace(corrected, "the")

        assertNull(history.undo(untouched))
        val edit = history.undo(corrected)

        assertEquals("the", edit?.before)
        assertEquals("teh", edit?.after)
        assertEquals("teh", history.words().single { it.id == corrected }.current)
    }

    @Test
    fun `cursor lookup returns session words around the cursor and can be cleared`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 3, "one", listOf("one"))
        history.record(4, 7, "two", listOf("two"))

        assertEquals(listOf(first), history.aroundCursor(2).map { it.id })
        assertEquals(listOf(first, first + 1), history.aroundCursor(20).map { it.id })

        history.clear()

        assertEquals(emptyList<SessionWord>(), history.words())
    }

    @Test
    fun `deleting a range removes intersecting words and shifts later ranges`() {
        val history = SessionCorrectionHistory()
        history.record(0, 3, "one", listOf("one"))
        val deleted = history.record(4, 7, "two", listOf("two"))
        val later = history.record(8, 12, "later", listOf("later"))

        history.deleteRange(4, 7)

        assertEquals(null, history.words().firstOrNull { it.id == deleted })
        assertEquals(5, history.words().single { it.id == later }.start)
        assertEquals(9, history.words().single { it.id == later }.end)
    }
}
