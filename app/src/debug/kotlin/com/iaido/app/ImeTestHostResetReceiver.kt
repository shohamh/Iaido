package com.iaido.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-only cross-process bridge for resetting the active host editor. */
class ImeTestHostResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ImeHostResetProtocol.ACTION_RESET_EDITOR) return
        val requestId = intent.getLongExtra(ImeHostResetProtocol.EXTRA_REQUEST_ID, -1L)
        if (requestId >= 0L) ImeTestHostActivity.activeInstance()?.resetEditorFromTest(requestId)
    }
}
