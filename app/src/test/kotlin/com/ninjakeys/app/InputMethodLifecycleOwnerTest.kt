package com.ninjakeys.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InputMethodLifecycleOwnerTest {
    @Test
    fun `lifecycle follows input method view visibility`() {
        val owner = InputMethodLifecycleOwner { LifecycleRegistry.createUnsafe(it) }

        owner.onCreate()
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)

        owner.onStartInputView()
        assertEquals(Lifecycle.State.STARTED, owner.lifecycle.currentState)

        owner.onFinishInputView()
        assertEquals(Lifecycle.State.CREATED, owner.lifecycle.currentState)

        owner.onDestroy()
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
    }
}
