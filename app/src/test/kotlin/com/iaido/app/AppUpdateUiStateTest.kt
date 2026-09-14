package com.iaido.app

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppUpdateUiStateTest {
    @Test
    fun `busy states disable the update action`() {
        assertEquals(false, appUpdateButtonEnabled(AppUpdateUiState.Checking))
        assertEquals(false, appUpdateButtonEnabled(AppUpdateUiState.Downloading))
        assertEquals(true, appUpdateButtonEnabled(AppUpdateUiState.Idle))
    }

    @Test
    fun `ready state offers installation`() {
        val state = AppUpdateUiState.ReadyToInstall(File("/tmp/update.apk"), 5L)

        assertEquals("Install update", appUpdateButtonLabel(state))
        assertEquals("Update downloaded. Tap Install update to continue.", appUpdateStatusLabel(state))
    }

    @Test
    fun `permission state explains the Android action`() {
        assertEquals(
            "Allow Iaido to install updates, then press Update app again.",
            appUpdateStatusLabel(AppUpdateUiState.PermissionRequired),
        )
    }

    @Test
    fun `failure state exposes the actionable error`() {
        assertEquals(
            "Update failed: network unavailable",
            appUpdateStatusLabel(AppUpdateUiState.Failed("network unavailable")),
        )
    }

    @Test
    fun `signing mismatch explains the debug build migration`() {
        assertEquals(
            "Update failed: This release is signed for the production app. Uninstall the debug Iaido app, then install this update.",
            appUpdateStatusLabel(AppUpdateUiState.Failed(APP_UPDATE_SIGNING_MISMATCH_REASON)),
        )
    }
}
