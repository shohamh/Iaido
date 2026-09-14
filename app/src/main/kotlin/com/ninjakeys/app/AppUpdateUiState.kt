package com.ninjakeys.app

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
    AppUpdateUiState.Checking -> "Checking for the newest NinjaKeys release…"
    AppUpdateUiState.Downloading -> "Downloading the newest NinjaKeys release…"
    AppUpdateUiState.UpToDate -> "NinjaKeys is up to date."
    is AppUpdateUiState.ReadyToInstall -> "Update downloaded. Tap Install update to continue."
    AppUpdateUiState.PermissionRequired -> "Allow NinjaKeys to install updates, then press Update app again."
    is AppUpdateUiState.Failed -> "Update failed: ${state.message}"
}
