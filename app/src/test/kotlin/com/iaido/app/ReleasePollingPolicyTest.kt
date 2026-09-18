package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReleasePollingPolicyTest {
    @Test
    fun `normal polling waits ten minutes`() {
        assertEquals(
            RELEASE_POLL_NORMAL_DELAY_MS,
            ReleasePollingPolicy.nextDelayMs(nowMs = 1_000L, state = ReleaseMonitorState()),
        )
    }

    @Test
    fun `running workflow switches to one minute polling`() {
        val state = ReleaseMonitorState().startFastPolling(nowMs = 10_000L, workflowId = 7L)

        assertEquals(RELEASE_POLL_FAST_DELAY_MS, ReleasePollingPolicy.nextDelayMs(10_001L, state))
        assertTrue(state.fastPollingUntilMs > 10_000L)
    }

    @Test
    fun `fast polling expires after one hour`() {
        val state = ReleaseMonitorState().startFastPolling(nowMs = 10_000L, workflowId = 7L)

        assertEquals(
            RELEASE_POLL_NORMAL_DELAY_MS,
            ReleasePollingPolicy.nextDelayMs(state.fastPollingUntilMs, state),
        )
        assertFalse(state.isFastPolling(state.fastPollingUntilMs))
    }

    @Test
    fun `same workflow cannot renew an expired fast polling window`() {
        val state = ReleaseMonitorState()
            .startFastPolling(nowMs = 10_000L, workflowId = 7L)
        val expired = state.startFastPolling(state.fastPollingUntilMs + 1L, workflowId = 7L)

        assertEquals(state.fastPollingUntilMs, expired.fastPollingUntilMs)
    }

    @Test
    fun `same release identity is deduplicated`() {
        val identity = ReleaseIdentity(UpdateChannel.NIGHTLY, "nightly", 7L, "2026-09-18T18:22:41Z")

        assertEquals(identity, identity.copy())
        assertEquals(identity.hashCode(), identity.copy().hashCode())
    }
}
