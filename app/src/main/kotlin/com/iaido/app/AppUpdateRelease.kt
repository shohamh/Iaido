package com.iaido.app

import java.net.URI

data class AppReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
    val sizeBytes: Long?,
    val updatedAt: String = "",
)

data class AppRelease(
    val tagName: String,
    val assets: List<AppReleaseAsset>,
)

fun selectApkAsset(release: AppRelease): AppReleaseAsset {
    val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
    require(apkAssets.size == 1) {
        when (apkAssets.size) {
            0 -> "GitHub release ${release.tagName} has no APK asset"
            else -> "GitHub release ${release.tagName} has multiple APK assets"
        }
    }
    val asset = apkAssets.single()
    require(isTrustedAppReleaseUrl(asset.browserDownloadUrl)) {
        "APK asset URL is not a trusted Iaido GitHub release URL"
    }
    return asset
}

internal fun isTrustedAppReleaseUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(GITHUB_HOST, ignoreCase = true) &&
        uri.path.startsWith(RELEASE_DOWNLOAD_PREFIX)
}.getOrDefault(false)

private const val GITHUB_HOST = "github.com"
private const val RELEASE_DOWNLOAD_PREFIX = "/shohamh/Iaido/releases/download/"
