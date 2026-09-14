package com.ninjakeys.app

import com.ninjakeys.core.dictionary.WordEntry
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.layout.KeyboardLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SplitTypingControllerTest {
    private fun controller(committed: MutableList<String>) = SplitTypingController(
        dictionary = { listOf(WordEntry("there", 2.0), WordEntry("three", 1.0)) },
        commitText = committed::add,
    )

    @Test
    fun `touch down order merges two partials after grace`() {
        val committed = mutableListOf<String>()
        val split = controller(committed)

        split.begin(1, GesturePoint(0f, 0f, 0), 0)
        split.begin(2, GesturePoint(1f, 0f, 20), 20)
        split.end(2, 100, "ere")
        split.end(1, 120, "th")

        assertEquals(null, split.poll(469))
        assertEquals("there", split.poll(470))
        assertEquals(listOf("there"), committed)
    }

    @Test
    fun `single path remains a valid split result and outside tap is ignored`() {
        val committed = mutableListOf<String>()
        val split = controller(committed)

        split.begin(1, GesturePoint(0f, 0f, 0), 0)
        split.end(1, 100, "there")
        assertEquals("there", split.poll(450))
        assertEquals(false, split.tap(2, 'x', 900))
    }

    @Test
    fun `finish converts a captured path into letters before merging`() {
        val committed = mutableListOf<String>()
        val split = controller(committed)
        val layout = KeyboardLayout.qwertyTestLayout()
        val path = GesturePath(
            "there".mapIndexed { index, letter ->
                layout.centerOf(letter).let { GesturePoint(it.x, it.y, index * 10L) }
            },
        )

        split.begin(1, path.points.first(), 0)
        split.finish(1, path, layout, 100)

        assertEquals("there", split.poll(450))
        assertEquals(listOf("there"), committed)
    }
}
