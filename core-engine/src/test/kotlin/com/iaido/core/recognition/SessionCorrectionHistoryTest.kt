package com.iaido.core.recognition

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
    fun `repeated replacement uses the updated span after a word grows and shrinks`() {
        val history = SessionCorrectionHistory()
        val hey = history.record(0, 3, "hey", listOf("hey", "heyday"))

        history.replace(hey, "heyday")

        assertEquals(6, history.words().single { it.id == hey }.end)

        history.replace(hey, "hey")

        val restored = history.words().single { it.id == hey }
        assertEquals(3, restored.end)
        assertEquals("hey", restored.current)
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
    fun `cursor resting at a just-finished word's end still includes the earlier word`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 5, "there", listOf("there"))
        val second = history.record(6, 11, "world", listOf("world"))

        assertEquals(listOf(first, second), history.aroundCursor(11).map { it.id })
    }

    @Test
    fun `joining two adjacent entries produces one entry with the combined span and text`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 2, "wh", listOf("wh"))
        val second = history.record(3, 5, "at", listOf("at"))

        val edit = history.join(first, second, "what")

        assertEquals(0, edit?.start)
        assertEquals(5, edit?.end)
        assertEquals("wh", edit?.before)
        assertEquals("what", edit?.after)
        assertEquals(1, history.words().size)
        val merged = history.words().single()
        assertEquals(first, merged.id)
        assertEquals(0, merged.start)
        assertEquals(4, merged.end)
        assertEquals("what", merged.current)
        assertEquals(null, history.words().firstOrNull { it.id == second })
    }

    @Test
    fun `joining shifts later entries' positions by the length delta`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 2, "wh", listOf("wh"))
        val second = history.record(3, 5, "at", listOf("at"))
        val later = history.record(6, 11, "world", listOf("world"))

        history.join(first, second, "what")

        // "wh"+"at" (span 0..5, i.e. "wh at") becomes "what" (length 4): delta = 4 - 5 = -1.
        assertEquals(5, history.words().single { it.id == later }.start)
        assertEquals(10, history.words().single { it.id == later }.end)
    }

    @Test
    fun `joining non-adjacent ids returns null and changes nothing`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 2, "wh", listOf("wh"))
        history.record(3, 5, "at", listOf("at"))
        val third = history.record(6, 8, "hi", listOf("hi"))

        assertNull(history.join(first, third, "whhi"))
        assertEquals(3, history.words().size)
    }

    @Test
    fun `joining an unknown id returns null`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 2, "wh", listOf("wh"))
        history.record(3, 5, "at", listOf("at"))

        assertNull(history.join(first, 999, "what"))
        assertNull(history.join(999, first, "what"))
        assertEquals(2, history.words().size)
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

    @Test
    fun `snapshot preserves corrected entries and next id allocation`() {
        val history = SessionCorrectionHistory()
        val first = history.record(0, 3, "teh", listOf("teh", "the"))
        history.record(4, 9, "world", listOf("world"))
        history.replace(first, "the")

        val snapshot = history.snapshot()
        val restored = SessionCorrectionHistory()
        restored.restore(snapshot)

        assertEquals(snapshot, restored.snapshot())
        assertEquals(first + 2, restored.record(10, 13, "new", listOf("new")))
    }

    @Test
    fun `typed span refresh keeps one id while the word grows and candidates change`() {
        val history = SessionCorrectionHistory()

        val first = history.upsertTyped(0, 1, "h", emptyList())
        val same = history.upsertTyped(0, 2, "hi", listOf("his"))

        assertEquals(first, same)
        assertEquals(1, history.words().size)
        assertEquals("hi", history.words().single().current)
        assertEquals(listOf("hi", "his"), history.words().single().candidates)
    }

    @Test
    fun `multi-word replacement keeps output words addressable and records a composite group`() {
        val history = SessionCorrectionHistory()
        val source = history.record(0, 6, "inthe", listOf("inthe"))

        val ids = history.replaceRange(0, 6, listOf("in", "the"), listOf(emptyList(), emptyList()))

        assertEquals(source, ids.first())
        assertEquals(listOf("in", "the"), history.words().map { it.current })
        assertEquals(1, history.groups().size)
        assertEquals(ids, history.groups().single().wordIds)
        assertEquals(2, history.groups().single().replacementWords.size)
    }

    @Test
    fun `editing a split member breaks its composite group but preserves independent reels`() {
        val history = SessionCorrectionHistory()
        val ids = history.replaceRange(0, 6, listOf("in", "the"))

        val broken = history.breakCompositeGroupFor(ids.last())

        assertEquals(ids, broken?.wordIds)
        assertEquals(emptyList<ReelGroup>(), history.groups())
        assertEquals(listOf("in", "the"), history.words().map { it.current })
    }

    @Test
    fun `deleting one split range removes the group and leaves later entries shifted`() {
        val history = SessionCorrectionHistory()
        history.replaceRange(0, 6, listOf("in", "the"))
        val later = history.record(7, 12, "world", listOf("world"))

        history.deleteRange(3, 6)

        assertEquals(emptyList<ReelGroup>(), history.groups())
        assertEquals(4, history.words().single { it.id == later }.start)
    }
}
