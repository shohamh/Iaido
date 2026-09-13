package com.ninjakeys.core.commands

enum class GestureAction { SWITCH_LANGUAGE, DISMISS, UNDO, REDO, CUT, COPY, PASTE, SELECT_ALL }

data class CommandBinding(val slot: String, val trigger: String, val action: GestureAction)

class CommandBindingSet(initial: List<CommandBinding> = defaultBindings) {
    private val mutableBindings = initial.toMutableList()
    val bindings: List<CommandBinding> get() = mutableBindings.toList()

    fun actionFor(trigger: String): GestureAction? = mutableBindings.firstOrNull { it.trigger == trigger }?.action

    fun rebind(slot: String, trigger: String, action: GestureAction) {
        require(mutableBindings.none { it.slot != slot && it.trigger == trigger }) { "Trigger is already assigned" }
        val index = mutableBindings.indexOfFirst { it.slot == slot }
        require(index >= 0) { "Unknown command slot" }
        mutableBindings[index] = CommandBinding(slot, trigger, action)
    }

    private companion object {
        val defaultBindings = listOf(
            CommandBinding("language", "two-finger-horizontal", GestureAction.SWITCH_LANGUAGE),
            CommandBinding("dismiss", "two-finger-down", GestureAction.DISMISS),
            CommandBinding("undo", "two-finger-left", GestureAction.UNDO),
            CommandBinding("redo", "two-finger-right", GestureAction.REDO),
            CommandBinding("command-mode", "long-press-space", GestureAction.COPY),
        )
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
