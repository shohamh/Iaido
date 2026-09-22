package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackspaceGestureMathTest {
    @Test
    fun `backspace swipes choose delete undo or redo by dominant direction`() {
        assertEquals(BackspaceGestureAction.DELETE, classifyBackspaceGesture(-80f, 8f, 24f))
        assertEquals(BackspaceGestureAction.UNDO, classifyBackspaceGesture(-8f, -80f, 24f))
        assertEquals(BackspaceGestureAction.REDO, classifyBackspaceGesture(8f, 80f, 24f))
    }

    @Test
    fun `RTL backspace swipe deletes to the right while LTR deletes to the left`() {
        assertEquals(BackspaceGestureAction.DELETE, classifyBackspaceGesture(80f, 8f, 24f, isRtl = true))
        assertEquals(BackspaceGestureAction.TAP, classifyBackspaceGesture(-80f, 8f, 24f, isRtl = true))
        assertEquals(4, backspaceSwipeRequestedCharacters(80f, 20f, isRtl = true))
        assertEquals(4, backspaceSwipeRequestedCharacters(-80f, 20f, isRtl = false))
    }

    @Test
    fun `short backspace movement stays a tap`() {
        assertEquals(BackspaceGestureAction.TAP, classifyBackspaceGesture(8f, 8f, 24f))
    }

    @Test
    fun `backspace repeat interval accelerates without exceeding safe speed`() {
        assertEquals(200L, backspaceRepeatIntervalMs(repeatCount = 0))
        assertEquals(125L, backspaceRepeatIntervalMs(repeatCount = 5))
        assertEquals(70L, backspaceRepeatIntervalMs(repeatCount = 20))
    }

    @Test
    fun `held backspace switches from characters to words after acceleration`() {
        assertEquals(false, backspaceRepeatDeletesWord(repeatCount = 1))
        assertEquals(false, backspaceRepeatDeletesWord(repeatCount = 7))
        assertEquals(true, backspaceRepeatDeletesWord(repeatCount = 8))
    }

    @Test
    fun `swipe deletion stays precise but snaps near word endings`() {
        assertEquals(3, deletionCountForSwipe(3, "one two three", 60))
        assertEquals(6, deletionCountForSwipe(6, "one two three", 60))
    }

    @Test
    fun `maximum swipe deletion ends at the last complete word boundary`() {
        val beforeCursor = (1..20).joinToString(" ") { "word" }

        assertEquals(60, deletionCountForSwipe(100, beforeCursor, 60))
    }

    @Test
    fun `maximum swipe deletion still works when the current word exceeds the cap`() {
        assertEquals(60, deletionCountForSwipe(100, "a".repeat(80), 60))
    }
}
