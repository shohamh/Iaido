package com.ninjakeys.core.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class CommandBindingsTest {
    @Test
    fun `defaults bind horizontal language switch and editing commands`() {
        val bindings = CommandBindingSet()

        assertEquals(GestureAction.SWITCH_LANGUAGE, bindings.actionFor("two-finger-horizontal"))
        assertEquals(GestureAction.UNDO, bindings.actionFor("two-finger-left"))
        assertEquals(GestureAction.REDO, bindings.actionFor("two-finger-right"))
    }

    @Test
    fun `rebinding rejects a trigger already owned by another slot`() {
        val bindings = CommandBindingSet()

        assertThrows<IllegalArgumentException> {
            bindings.rebind("undo", "two-finger-horizontal", GestureAction.UNDO)
        }
    }

    @Test
    fun `dispatcher invokes the action mapped to a trigger`() {
        val actions = mutableListOf<GestureAction>()
        val dispatcher = CommandGestureDispatcher(CommandBindingSet()) { actions += it }

        assertEquals(true, dispatcher.dispatch("two-finger-left"))
        assertEquals(listOf(GestureAction.UNDO), actions)
        assertEquals(false, dispatcher.dispatch("unknown"))
    }
}
