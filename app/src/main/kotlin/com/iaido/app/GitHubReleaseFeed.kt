package com.iaido.app

import java.net.URI

internal data class PublicRelease(
    val tagName: String,
    val versionLabel: String,
    val updatedAt: String,
)

internal fun selectAtomRelease(xml: String, channel: UpdateChannel): PublicRelease? {
    val releases = atomEntryPattern.findAll(xml).mapNotNull { match ->
        val entry = match.groupValues[1]
        val tagName = atomTagName(entry) ?: return@mapNotNull null
        PublicRelease(
            tagName = tagName,
            versionLabel = atomText(entry, "title") ?: tagName,
            updatedAt = atomText(entry, "updated").orEmpty(),
        )
    }

    return when (channel) {
        UpdateChannel.NIGHTLY -> releases
            .filter { it.tagName.matches(NIGHTLY_TAG_PATTERN) }
            .maxByOrNull(PublicRelease::tagName)
        UpdateChannel.STABLE -> releases
            .firstOrNull { !it.tagName.matches(NIGHTLY_TAG_PATTERN) && it.tagName != "nightly" }
    }
}

internal fun publicReleaseAppRelease(release: PublicRelease): AppRelease = AppRelease(
    tagName = release.tagName,
    assets = listOf(
        AppReleaseAsset(
            name = "app-release.apk",
            browserDownloadUrl = "https://github.com/shohamh/Iaido/releases/download/" +
                "${release.tagName}/app-release.apk",
            sizeBytes = null,
            updatedAt = release.updatedAt,
        ),
    ),
)

internal fun latestStableAppRelease(): AppRelease = AppRelease(
    tagName = "latest",
    assets = listOf(
        AppReleaseAsset(
            name = "app-release.apk",
            browserDownloadUrl = STABLE_LATEST_APK_URL,
            sizeBytes = null,
        ),
    ),
)

internal fun releaseTagFromDownloadUrl(value: String): String? = runCatching {
    val path = URI(value).path.orEmpty()
    val marker = "/releases/download/"
    path.substringAfter(marker, "")
        .substringBefore('/')
        .takeIf { it.isNotBlank() }
}.getOrNull()

private fun atomTagName(entry: String): String? {
    val href = Regex(
        "<link\\s+[^>]*rel=\"alternate\"[^>]*href=\"([^\"]+)\"",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(entry)?.groupValues?.getOrNull(1) ?: return null
    return releaseTagFromReleasePageUrl(href)
}

private fun releaseTagFromReleasePageUrl(value: String): String? = runCatching {
    val path = URI(value).path.orEmpty()
    path.substringAfter("/releases/tag/", "")
        .takeIf { it.isNotBlank() }
}.getOrNull()

private fun atomText(entry: String, element: String): String? = Regex(
    "<$element(?:\\s[^>]*)?>(.*?)</$element>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
).find(entry)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

private val atomEntryPattern = Regex(
    "<entry(?:\\s[^>]*)?>(.*?)</entry>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val NIGHTLY_TAG_PATTERN = Regex("nightly-\\d{12}")

internal const val GITHUB_RELEASES_ATOM_URL = "https://github.com/shohamh/Iaido/releases.atom"
internal const val STABLE_LATEST_APK_URL =
    "https://github.com/shohamh/Iaido/releases/latest/download/app-release.apk"
