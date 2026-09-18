package com.iaido.app

/** Tracks one restore revision until both Android runtime bindings are usable. */
internal class KeyboardRuntimeReadiness {
    private var nextRevision = 0L
    private var activeRevision: Long? = null
    private var inputConnectionRevision: Long? = null
    private var inputViewRevision: Long? = null

    fun beginRestore(): Long {
        nextRevision += 1
        activeRevision = nextRevision
        inputConnectionRevision = null
        inputViewRevision = null
        return nextRevision
    }

    fun markInputConnectionBound(revision: Long) {
        if (activeRevision == revision) inputConnectionRevision = revision
    }

    fun markInputViewRendered(revision: Long) {
        if (activeRevision == revision) inputViewRevision = revision
    }

    fun readyRevision(): Long? = activeRevision
        ?.takeIf { it == inputConnectionRevision && it == inputViewRevision }

    fun isReady(revision: Long): Boolean = readyRevision() == revision

    fun invalidate() {
        activeRevision = null
        inputConnectionRevision = null
        inputViewRevision = null
    }
}
