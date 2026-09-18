package com.iaido.core.commands

enum class GestureAction { SWITCH_LANGUAGE, DISMISS, UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL, ENTER_COMMAND_MODE }

enum class GestureTrigger {
    NONE, HORIZONTAL, UP, DOWN, LEFT, RIGHT, LONG_PRESS_SPACE, COMMAND_CUT, COMMAND_COPY,
    COMMAND_PASTE, COMMAND_SELECT_ALL,
}

data class CommandBinding(val slot: String, val trigger: String, val action: GestureAction)

class CommandBindingSet(initial: List<CommandBinding> = defaultBindings) {
    private val mutableBindings = initial.toMutableList()
    init {
        validate(mutableBindings)
    }
    val bindings: List<CommandBinding> get() = mutableBindings.toList()

    fun replaceAll(replacements: List<CommandBinding>) {
        validate(replacements)
        mutableBindings.clear()
        mutableBindings += replacements
    }

    fun actionFor(trigger: String): GestureAction? = mutableBindings.firstOrNull { it.trigger == trigger }?.action

    fun rebind(slot: String, trigger: String, action: GestureAction) {
        require(mutableBindings.none { it.slot != slot && it.trigger == trigger }) { "Trigger is already assigned" }
        val index = mutableBindings.indexOfFirst { it.slot == slot }
        require(index >= 0) { "Unknown command slot" }
        mutableBindings[index] = CommandBinding(slot, trigger, action)
    }

    private companion object {
        fun validate(bindings: List<CommandBinding>) {
            require(bindings.map { it.slot }.distinct().size == bindings.size) {
                "Command slots must be unique"
            }
            require(bindings.map { it.trigger }.distinct().size == bindings.size) {
                "Command triggers must be unique"
            }
        }

        val defaultBindings = listOf(
            CommandBinding("language", "two-finger-horizontal", GestureAction.SWITCH_LANGUAGE),
            CommandBinding("dismiss", "two-finger-down", GestureAction.DISMISS),
            CommandBinding("undo", "two-finger-left", GestureAction.UNDO),
            CommandBinding("redo", "two-finger-right", GestureAction.REDO),
            CommandBinding("command-mode", "long-press-space", GestureAction.ENTER_COMMAND_MODE),
        )
    }
}

class MultiFingerGestureDetector(private val minimumMovement: Float) {
    fun detect(startX: Float, startY: Float, endX: Float, endY: Float, pointerCount: Int): GestureTrigger {
        if (pointerCount < 2) return GestureTrigger.NONE
        val dx = endX - startX
        val dy = endY - startY
        return when {
            kotlin.math.abs(dx) >= minimumMovement && kotlin.math.abs(dx) > kotlin.math.abs(dy) -> GestureTrigger.HORIZONTAL
            dy <= -minimumMovement && kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> GestureTrigger.UP
            dy >= minimumMovement && kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> GestureTrigger.DOWN
            else -> GestureTrigger.NONE
        }
    }
}

class CommandModeController(private val execute: (GestureAction) -> Unit) {
    private var active = false
    val isActive: Boolean get() = active

    fun handle(trigger: GestureTrigger): Boolean {
        if (trigger == GestureTrigger.LONG_PRESS_SPACE) {
            active = true
            execute(GestureAction.ENTER_COMMAND_MODE)
            return true
        }
        if (!active) return false
        val action = mapOf(
            GestureTrigger.COMMAND_CUT to GestureAction.CUT,
            GestureTrigger.COMMAND_COPY to GestureAction.COPY,
            GestureTrigger.COMMAND_PASTE to GestureAction.PASTE,
            GestureTrigger.COMMAND_SELECT_ALL to GestureAction.SELECT_ALL,
        )[trigger] ?: return false
        active = false
        execute(action)
        return true
    }
}

class CommandGestureDispatcher(
    private val bindings: CommandBindingSet,
    private val execute: (GestureAction) -> Unit,
) {
    fun dispatch(trigger: String): Boolean = bindings.actionFor(trigger)?.let {
        execute(it)
        true
    } ?: false
}
