package com.iaido.app

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppUpdateUiStateTest {
    @Test
    fun `busy states disable the update action`() {
        assertEquals(false, appUpdateButtonEnabled(AppUpdateUiState.Checking))
        assertEquals(false, appUpdateButtonEnabled(AppUpdateUiState.Downloading()))
        assertEquals(true, appUpdateButtonEnabled(AppUpdateUiState.Idle))
    }

    @Test
    fun `downloading state exposes progress and eta`() {
        val state = AppUpdateUiState.Downloading(
            downloadedBytes = 4L,
            totalBytes = 10L,
            etaMillis = 3_000L,
        )

        assertEquals(0.4f, state.progressFraction)
        assertEquals(
            "Downloading the newest Iaido release… 40% · 4 B / 10 B · about 3 seconds remaining",
            appUpdateStatusLabel(state),
        )
    }

    @Test
    fun `eta is estimated from observed byte rate`() {
        assertEquals(
            3_000L,
            estimateDownloadRemainingMillis(downloadedBytes = 4L, totalBytes = 10L, elapsedMillis = 2_000L),
        )
        assertEquals(
            null,
            estimateDownloadRemainingMillis(downloadedBytes = 0L, totalBytes = 10L, elapsedMillis = 2_000L),
        )
    }

    @Test
    fun `ready state offers installation`() {
        val state = AppUpdateUiState.ReadyToInstall(File("/tmp/update.apk"), 5L)

        assertEquals("Check for updates", appUpdateButtonLabel(state))
        assertEquals(true, appUpdateInstallButtonEnabled(state))
        assertEquals("Update downloaded. Tap Install update to continue.", appUpdateStatusLabel(state))
    }

    @Test
    fun `pending release is shown while the GitHub action is running`() {
        assertEquals("Release pending — check again in a few minutes.", appUpdateStatusLabel(AppUpdateUiState.PendingRelease))
        assertEquals(false, appUpdateInstallButtonEnabled(AppUpdateUiState.PendingRelease))
    }

    @Test
    fun `permission state explains the Android action`() {
        assertEquals(
            "Allow Iaido to install updates, then press Check for updates again.",
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

    @Test
    fun `missing nightly release explains that the build is still publishing`() {
        assertEquals(
            "Nightly is not available yet. Try again after the nightly build finishes.",
            appUpdateStatusLabel(AppUpdateUiState.Failed(APP_UPDATE_NIGHTLY_UNAVAILABLE_REASON)),
        )
    }
}
