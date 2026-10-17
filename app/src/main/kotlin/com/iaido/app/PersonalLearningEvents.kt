package com.iaido.app

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide notifications so Settings edits to persisted learning (forget / reset) also reach
 * the running IME's in-memory dictionary, which would otherwise keep serving the forgotten word.
 */
object PersonalLearningEvents {
    interface Listener {
        fun onForgotten(word: String)
        fun onReset()
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun register(listener: Listener) { listeners += listener }
    fun unregister(listener: Listener) { listeners -= listener }
    fun notifyForgotten(word: String) = listeners.forEach { it.onForgotten(word) }
    fun notifyReset() = listeners.forEach { it.onReset() }
}
