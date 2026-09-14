package com.iaido.app

import java.io.File

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data object Downloading : AppUpdateUiState
    data object UpToDate : AppUpdateUiState
    data class ReadyToInstall(val apk: File, val versionCode: Long) : AppUpdateUiState
    data object PermissionRequired : AppUpdateUiState
    data class Failed(val message: String) : AppUpdateUiState
}

fun appUpdateButtonEnabled(state: AppUpdateUiState): Boolean =
    state !is AppUpdateUiState.Checking && state !is AppUpdateUiState.Downloading

fun appUpdateButtonLabel(state: AppUpdateUiState): String = when (state) {
    is AppUpdateUiState.ReadyToInstall -> "Install update"
    else -> "Update app"
}

fun appUpdateStatusLabel(state: AppUpdateUiState): String = when (state) {
    AppUpdateUiState.Idle -> ""
    AppUpdateUiState.Checking -> "Checking for the newest Iaido release…"
    AppUpdateUiState.Downloading -> "Downloading the newest Iaido release…"
    AppUpdateUiState.UpToDate -> "Iaido is up to date."
    is AppUpdateUiState.ReadyToInstall -> "Update downloaded. Tap Install update to continue."
    AppUpdateUiState.PermissionRequired -> "Allow Iaido to install updates, then press Update app again."
    is AppUpdateUiState.Failed -> when (state.message) {
        APP_UPDATE_SIGNING_MISMATCH_REASON ->
            "Update failed: This release is signed for the production app. Uninstall the debug Iaido app, then install this update."
        else -> "Update failed: ${state.message}"
    }
}
