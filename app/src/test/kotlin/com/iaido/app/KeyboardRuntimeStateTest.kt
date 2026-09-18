package com.iaido.app

import com.iaido.core.language.Language
import com.iaido.core.recognition.SessionCorrectionHistorySnapshot
import com.iaido.core.recognition.SessionWord
import com.iaido.core.state.TypingSessionSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KeyboardRuntimeStateTest {
    @Test
    fun `restores a quiescent session and increments one revision`() {
        val runtime = KeyboardRuntimeState()
        val snapshot = sessionSnapshot()

        val revision = runtime.restore(snapshot)

        assertEquals(1L, revision)
        assertEquals(snapshot, runtime.snapshot())
    }

    @Test
    fun `refuses snapshots while transient gesture work is active`() {
        val runtime = KeyboardRuntimeState()
        runtime.beginTransientWork()

        assertThrows(IllegalStateException::class.java) { runtime.snapshot() }
        assertThrows(IllegalStateException::class.java) { runtime.restore(sessionSnapshot()) }

        runtime.finishTransientWork()
        assertEquals(0L, runtime.revision())
    }

    @Test
    fun `runtime readiness requires the restored revision and both runtime bindings`() {
        val readiness = KeyboardRuntimeReadiness()
        val revision = readiness.beginRestore()

        readiness.markInputConnectionBound(revision)
        assertEquals(null, readiness.readyRevision())

        readiness.markInputViewRendered(revision)
        assertEquals(revision, readiness.readyRevision())
        assertEquals(true, readiness.isReady(revision))

        readiness.invalidate()
        assertEquals(false, readiness.isReady(revision))
    }

    private fun sessionSnapshot() = TypingSessionSnapshot(
        language = Language.ENGLISH,
        correctionHistory = SessionCorrectionHistorySnapshot(
            nextId = 1,
            entries = listOf(SessionWord(0, 0, 3, "teh", "the", listOf("teh", "the"), true)),
        ),
        cursorPosition = 3,
        pendingCandidates = listOf("the"),
    )
}
