package com.iaido.app

/** Tracks the poll chain that belongs to the current physical gesture. */
internal class SplitGesturePollLifecycle {
    var currentGeneration = 0L
        private set

    var scheduledGeneration: Long? = null
        private set

    fun startGesture(): Long {
        invalidate()
        return currentGeneration
    }

    fun invalidate() {
        currentGeneration += 1
        scheduledGeneration = null
    }

    fun scheduleCurrentGeneration(): Long? {
        if (scheduledGeneration == currentGeneration) return null
        scheduledGeneration = currentGeneration
        return currentGeneration
    }

    fun isCurrent(generation: Long): Boolean = currentGeneration == generation

    fun finishPolling(generation: Long) {
        if (isCurrent(generation) && scheduledGeneration == generation) {
            scheduledGeneration = null
        }
    }
}
