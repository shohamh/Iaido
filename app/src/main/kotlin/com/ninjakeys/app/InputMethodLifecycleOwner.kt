package com.ninjakeys.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

internal class InputMethodLifecycleOwner(
    registryFactory: (LifecycleOwner) -> LifecycleRegistry = { LifecycleRegistry(it) },
) : SavedStateRegistryOwner {
    private val registry = registryFactory(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override val lifecycle: Lifecycle
        get() = registry

    fun onCreate(restoredState: android.os.Bundle? = null) {
        if (restoredState != null) {
            savedStateController.performAttach()
            savedStateController.performRestore(restoredState)
        }
        registry.currentState = Lifecycle.State.CREATED
    }

    fun onStartInputView() {
        registry.currentState = Lifecycle.State.STARTED
    }

    fun onFinishInputView() {
        registry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
