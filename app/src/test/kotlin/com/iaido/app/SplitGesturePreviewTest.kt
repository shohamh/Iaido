package com.iaido.app

import com.iaido.core.gesture.GesturePoint
import com.iaido.core.layout.KeyboardLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SplitGesturePreviewTest {
    @Test
    fun `preview preserves split touch order and collapses repeated keys`() {
        val layout = KeyboardLayout.qwertyTestLayout()
        val first = "thh".mapIndexed { index, letter ->
            layout.centerOf(letter).let { GesturePoint(it.x, it.y, index.toLong()) }
        }
        val second = "ere".mapIndexed { index, letter ->
            layout.centerOf(letter).let { GesturePoint(it.x, it.y, (10 + index).toLong()) }
        }

        assertEquals("there", splitGesturePreview(listOf(first, second), layout))
    }
}
