package com.ninjakeys.core.testing

import com.ninjakeys.core.gesture.GesturePath
import com.ninjakeys.core.gesture.GesturePoint
import com.ninjakeys.core.layout.KeyboardLayout

object SwipeFixtures {
    fun pathThrough(layout: KeyboardLayout, word: String, startMs: Long = 0L): GesturePath =
        GesturePath(word.mapIndexed { index, letter ->
            val key = layout.centerOf(letter)
            GesturePoint(key.x, key.y, startMs + index * 10L)
        })
}
