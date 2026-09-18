package com.iaido.app

internal const val RELEASE_POLL_NORMAL_DELAY_MS = 10 * 60 * 1000L
internal const val RELEASE_POLL_FAST_DELAY_MS = 60 * 1000L
internal const val RELEASE_POLL_FAST_WINDOW_MS = 60 * 60 * 1000L

internal data class ReleaseIdentity(
    val channel: UpdateChannel,
    val tagName: String,
    val releaseId: Long,
    val assetUpdatedAt: String,
)

internal data class ReleaseMonitorState(
    val fastPollingUntilMs: Long = 0L,
    val fastPollingBaseline: ReleaseIdentity? = null,
    val lastStagedRelease: ReleaseIdentity? = null,
    val stagedVersionName: String = "",
    val lastNotifiedRelease: ReleaseIdentity? = null,
) {
    fun startFastPolling(nowMs: Long, baseline: ReleaseIdentity? = fastPollingBaseline): ReleaseMonitorState = copy(
        fastPollingUntilMs = maxOf(fastPollingUntilMs, nowMs + RELEASE_POLL_FAST_WINDOW_MS),
        fastPollingBaseline = baseline,
    )

    fun isFastPolling(nowMs: Long): Boolean = nowMs < fastPollingUntilMs

    fun stopFastPolling(): ReleaseMonitorState = copy(
        fastPollingUntilMs = 0L,
        fastPollingBaseline = null,
    )
}

internal object ReleasePollingPolicy {
    fun nextDelayMs(nowMs: Long, state: ReleaseMonitorState): Long =
        if (state.isFastPolling(nowMs)) RELEASE_POLL_FAST_DELAY_MS else RELEASE_POLL_NORMAL_DELAY_MS
}
