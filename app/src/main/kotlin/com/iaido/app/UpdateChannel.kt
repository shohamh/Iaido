package com.iaido.app

import androidx.datastore.preferences.core.stringPreferencesKey

enum class UpdateChannel(
    val displayName: String,
    val apiUrl: String,
    val releaseTag: String?,
) {
    STABLE(
        displayName = "Stable release",
        apiUrl = "https://api.github.com/repos/shohamh/Iaido/releases/latest",
        releaseTag = null,
    ),
    NIGHTLY(
        displayName = "Nightly",
        apiUrl = NIGHTLY_RELEASES_API_URL,
        releaseTag = null,
    ),
}

internal val updateChannelKey = stringPreferencesKey("update_channel")

internal fun updateChannelFromStoredValue(value: String?, versionName: String = ""): UpdateChannel = when (value) {
    "nightly" -> UpdateChannel.NIGHTLY
    "stable" -> UpdateChannel.STABLE
    else -> if (versionName.contains("-nightly")) UpdateChannel.NIGHTLY else UpdateChannel.STABLE
}

internal fun updateChannelStoredValue(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> "stable"
    UpdateChannel.NIGHTLY -> "nightly"
}
