package com.ninjakeys.core.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandModeTest {
    @Test
    fun `long press enters command mode and next command exits it`() {
        val actions = mutableListOf<GestureAction>()
        val mode = CommandModeController(actions::add)

        assertEquals(true, mode.handle(GestureTrigger.LONG_PRESS_SPACE))
        assertEquals(true, mode.handle(GestureTrigger.COMMAND_COPY))
        assertEquals(listOf(GestureAction.ENTER_COMMAND_MODE, GestureAction.COPY), actions)
        assertEquals(false, mode.handle(GestureTrigger.COMMAND_COPY))
    }

    @Test
    fun `command mode maps upward follow-up to cut`() {
        val actions = mutableListOf<GestureAction>()
        val mode = CommandModeController(actions::add)

        mode.handle(GestureTrigger.LONG_PRESS_SPACE)
        mode.handle(GestureTrigger.COMMAND_CUT)

        assertEquals(listOf(GestureAction.ENTER_COMMAND_MODE, GestureAction.CUT), actions)
    }
}
