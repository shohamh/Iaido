package com.iaido.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InferenceSelectionGuardTest {
    @Test
    fun `unarmed guard never suppresses`() {
        val guard = InferenceSelectionGuard(nowMs = { 0L })
        assertFalse(guard.shouldSuppress(5, 5, timeoutMs = 500))
    }

    @Test
    fun `suppresses an intermediate selection update that does not match the expected final selection`() {
        val guard = InferenceSelectionGuard(nowMs = { 0L })
        guard.arm(selectionStart = 8, selectionEnd = 8)

        // e.g. the framework reporting the transient setSelection(span.start, span.end) call
        assertTrue(guard.shouldSuppress(3, 6, timeoutMs = 500))
    }

    @Test
    fun `disarms once it observes the exact expected selection`() {
        val guard = InferenceSelectionGuard(nowMs = { 0L })
        guard.arm(selectionStart = 8, selectionEnd = 8)

        assertTrue(guard.shouldSuppress(8, 8, timeoutMs = 500))
        // A later, genuinely external selection change must not be suppressed anymore.
        assertFalse(guard.shouldSuppress(2, 2, timeoutMs = 500))
    }

    @Test
    fun `reproduces the race - a late callback after the synchronous edit call returned is still suppressed`() {
        // This is the exact regression this guard fixes: with a plain boolean flag cleared synchronously
        // right after the edit call returns, a callback delivered later (simulated here by time passing
        // and the guard still being armed) would already see the flag as false and wrongly treat this as
        // an external cursor move.
        var clock = 0L
        val guard = InferenceSelectionGuard(nowMs = { clock })
        guard.arm(selectionStart = 8, selectionEnd = 8)

        clock = 50L // the synchronous InputConnection calls have long since returned by now
        assertTrue(guard.shouldSuppress(8, 8, timeoutMs = 500))
    }

    @Test
    fun `times out and stops suppressing if the expected callback never arrives`() {
        var clock = 0L
        val guard = InferenceSelectionGuard(nowMs = { clock })
        guard.arm(selectionStart = 8, selectionEnd = 8)

        clock = 600L
        assertFalse(guard.shouldSuppress(2, 2, timeoutMs = 500))
    }

    @Test
    fun `clear disarms unconditionally`() {
        val guard = InferenceSelectionGuard(nowMs = { 0L })
        guard.arm(selectionStart = 8, selectionEnd = 8)
        guard.clear()

        assertFalse(guard.shouldSuppress(8, 8, timeoutMs = 500))
    }
}
