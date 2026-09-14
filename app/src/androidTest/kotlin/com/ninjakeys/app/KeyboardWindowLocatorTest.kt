package com.ninjakeys.app

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardWindowLocatorTest {
    @Test
    fun keyCenterIsRelativeToMarkedBounds() {
        assertEquals(150, centerOf(Rect(100, 200, 201, 301)).x)
        assertEquals(250, centerOf(Rect(100, 200, 201, 301)).y)
    }

    @Test
    fun navigationSafeBottomLeavesInsetBelowKeyboardSurface() {
        assertEquals(1_872, navigationSafeBottom(Rect(0, 0, 1_080, 1_920), 48))
        assertEquals(1_920, navigationSafeBottom(Rect(0, 0, 1_080, 1_920), 0))
    }

    @Test
    fun negativeNavigationInsetCannotMoveSafeBottomBelowDisplayTop() {
        assertEquals(1_920, navigationSafeBottom(Rect(0, 100, 1_080, 1_920), -50))
        assertEquals(100, navigationSafeBottom(Rect(0, 100, 1_080, 1_920), 5_000))
    }

    @Test
    fun missingMarkerMessageIncludesMarkerAndHierarchy() {
        val message = missingMarkerMessage("key 'space'", "<node text=\"editor\"/>")
        assertTrue(message.contains("key 'space'"))
        assertTrue(message.contains("<node text=\"editor\"/>") )
    }
}
