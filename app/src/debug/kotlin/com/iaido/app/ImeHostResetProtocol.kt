package com.iaido.app

/** Debug-only protocol shared by the host reset poller and instrumentation driver. */
internal object ImeHostResetProtocol {
    const val ACTION_RESET_EDITOR = "com.iaido.app.action.RESET_EDITOR"
    const val EXTRA_REQUEST_ID = "com.iaido.app.extra.EDITOR_RESET_REQUEST_ID"

    fun isAcknowledged(
        requestedId: Long,
        observedResetId: Long?,
        textLength: Int,
        selection: IntRange,
    ): Boolean = observedResetId == requestedId && textLength == 0 && selection == 0..0
}
