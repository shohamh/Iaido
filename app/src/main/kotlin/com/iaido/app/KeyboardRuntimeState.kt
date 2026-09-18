package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.state.TypingSessionSnapshot

/** Model-only state that can be checkpointed; Android runtime handles stay outside this module. */
internal class KeyboardRuntimeState {
    private var snapshot = TypingSessionSnapshot(
        language = Language.ENGLISH,
        correctionHistory = SessionCorrectionHistorySnapshot(nextId = 0, entries = emptyList()),
        cursorPosition = 0,
        pendingCandidates = emptyList(),
    )
    private var revision = 0L
    private var transientWorkActive = false

    fun snapshot(): TypingSessionSnapshot {
        check(!transientWorkActive) { "Typing-session state is not quiescent" }
        return snapshot
    }

    fun restore(snapshot: TypingSessionSnapshot): Long {
        check(!transientWorkActive) { "Cannot restore while transient typing work is active" }
        this.snapshot = snapshot
        revision += 1
        return revision
    }

    fun revision(): Long = revision

    fun beginTransientWork() {
        check(!transientWorkActive) { "Transient typing work is already active" }
        transientWorkActive = true
    }

    fun finishTransientWork() {
        transientWorkActive = false
    }
}
