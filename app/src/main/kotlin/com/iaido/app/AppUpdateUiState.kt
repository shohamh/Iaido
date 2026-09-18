package com.iaido.app

import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToLong

sealed interface AppUpdateUiState {
    data object Idle : AppUpdateUiState
    data object Checking : AppUpdateUiState
    data class Downloading(
        val downloadedBytes: Long = 0L,
        val totalBytes: Long? = null,
        val etaMillis: Long? = null,
    ) : AppUpdateUiState {
        val progressFraction: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { total -> (downloadedBytes.toDouble() / total).coerceIn(0.0, 1.0).toFloat() }
    }
    data object PendingRelease : AppUpdateUiState
    data object UpToDate : AppUpdateUiState
    data class ReadyToInstall(val apk: File, val versionCode: Long, val versionName: String = "") : AppUpdateUiState
    data object PermissionRequired : AppUpdateUiState
    data class Failed(val message: String) : AppUpdateUiState
}

fun appUpdateButtonEnabled(state: AppUpdateUiState): Boolean =
    state !is AppUpdateUiState.Checking && state !is AppUpdateUiState.Downloading

fun appUpdateInstallButtonEnabled(state: AppUpdateUiState): Boolean =
    state is AppUpdateUiState.ReadyToInstall

fun appUpdateButtonLabel(state: AppUpdateUiState): String = "Check for updates"

fun appUpdateStatusLabel(state: AppUpdateUiState): String = when (state) {
    AppUpdateUiState.Idle -> ""
    AppUpdateUiState.Checking -> "Checking for the newest Iaido release\u2026"
    is AppUpdateUiState.Downloading -> buildString {
        append("Downloading the newest Iaido release\u2026")
        state.progressFraction?.let { fraction ->
            append(" ${(fraction * 100).roundToLong()}%")
            state.totalBytes?.let { total ->
                append(" \u00b7 ${formatUpdateBytes(state.downloadedBytes)} / ${formatUpdateBytes(total)}")
            }
        }
        state.etaMillis?.let { eta -> append(" \u00b7 about ${formatEta(eta)} remaining") }
    }
    AppUpdateUiState.PendingRelease -> "Release pending \u2014 check again in a few minutes."
    AppUpdateUiState.UpToDate -> "Iaido is up to date."
    is AppUpdateUiState.ReadyToInstall -> "Update downloaded. Tap Install update to continue."
    AppUpdateUiState.PermissionRequired -> "Allow Iaido to install updates, then press Check for updates again."
    is AppUpdateUiState.Failed -> when (state.message) {
        APP_UPDATE_SIGNING_MISMATCH_REASON ->
            "Update failed: This release is signed for the production app. Uninstall the debug Iaido app, then install this update."
        APP_UPDATE_NIGHTLY_UNAVAILABLE_REASON ->
            "Nightly is not available yet. Try again after the nightly build finishes."
        else -> "Update failed: ${state.message}"
    }
}

internal fun estimateDownloadRemainingMillis(
    downloadedBytes: Long,
    totalBytes: Long?,
    elapsedMillis: Long,
): Long? {
    if (totalBytes == null || totalBytes <= 0L || downloadedBytes <= 0L || elapsedMillis <= 0L) return null
    if (downloadedBytes >= totalBytes) return 0L
    return (((totalBytes - downloadedBytes).toDouble() * elapsedMillis) / downloadedBytes)
        .roundToLong()
        .coerceAtLeast(0L)
}

private fun formatUpdateBytes(bytes: Long): String = when {
    bytes < 1_024L -> "${bytes} B"
    bytes < 1_024L * 1_024L -> "${bytes / 1_024L} KB"
    else -> "${"%.1f".format(java.util.Locale.US, bytes / (1_024.0 * 1_024.0))} MB"
}

private fun formatEta(etaMillis: Long): String {
    val seconds = ceil(etaMillis.coerceAtLeast(0L) / 1_000.0).toLong()
    return when {
        seconds == 1L -> "1 second"
        seconds < 60L -> "${seconds} seconds"
        else -> {
            val minutes = seconds / 60L
            val remainingSeconds = seconds % 60L
            if (remainingSeconds == 0L) "${minutes} minutes" else "${minutes} min ${remainingSeconds} sec"
        }
    }
}
