package com.iaido.app

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

internal sealed interface ReleaseProbe {
    data class Available(
        val identity: ReleaseIdentity,
        val versionLabel: String,
    ) : ReleaseProbe

    data object Unavailable : ReleaseProbe
}

internal sealed interface ReleaseWorkflowStatus {
    data class Running(val runId: Long) : ReleaseWorkflowStatus
    data object Idle : ReleaseWorkflowStatus
    data object Unknown : ReleaseWorkflowStatus
}

internal class GitHubReleaseMonitorClient(
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as? HttpURLConnection ?: error("Release URL is not HTTP")
    },
) {
    fun latestRelease(channel: UpdateChannel): ReleaseProbe {
        val connection = openConnection(URL(channel.apiUrl))
        return try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND && channel == UpdateChannel.NIGHTLY) {
                ReleaseProbe.Unavailable
            } else when (channel) {
                UpdateChannel.NIGHTLY -> {
                    checkResponse(connection)
                    selectAtomRelease(readResponse(connection), channel)?.let { release ->
                        ReleaseProbe.Available(
                            identity = ReleaseIdentity(channel, release.tagName, 0L, release.updatedAt),
                            versionLabel = release.versionLabel,
                        )
                    } ?: ReleaseProbe.Unavailable
                }
                UpdateChannel.STABLE -> {
                    checkResponse(connection)
                    val tagName = releaseTagFromDownloadUrl(connection.url.toString()) ?: "latest"
                    ReleaseProbe.Available(
                        identity = ReleaseIdentity(
                            channel = channel,
                            tagName = tagName,
                            releaseId = 0L,
                            assetUpdatedAt = connection.lastModified
                                .takeIf { it > 0L }
                                ?.toString()
                                .orEmpty(),
                        ),
                        versionLabel = tagName,
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun releaseWorkflowStatus(): ReleaseWorkflowStatus {
        // GitHub's Actions REST endpoint is rate-limited for unauthenticated clients.
        // Release detection already uses the public release feed, so polling falls back
        // to the normal schedule instead of repeatedly generating 403 responses.
        return ReleaseWorkflowStatus.Unknown
    }

    private fun openConnection(url: URL): HttpURLConnection = connectionFactory(url).apply {
        require(url.protocol.equals("https", ignoreCase = true)) { "Release URLs must use HTTPS" }
        connectTimeout = 5_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Iaido-App-Updater")
    }

    private fun checkResponse(connection: HttpURLConnection) {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            error("Release request failed: ${connection.responseCode}")
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val contentLength = connection.contentLengthLong
        if (contentLength > MAX_RESPONSE_BYTES) error("Release response is too large")
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) error("Release response is too large")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L
    }
}

internal fun parseReleaseProbe(json: String, channel: UpdateChannel): ReleaseProbe {
    val release = parseJsonObject(json)
    val assets = release.getValue("assets").jsonArray
    val appReleaseAssets = buildList {
        for (assetElement in assets) {
            val asset = assetElement.jsonObject
            add(
                AppReleaseAsset(
                    name = asset.requiredString("name"),
                    browserDownloadUrl = asset.requiredString("browser_download_url"),
                    sizeBytes = asset.optionalLong("size"),
                    updatedAt = asset.optionalString("updated_at"),
                ),
            )
        }
    }
    val tagName = release.requiredString("tag_name")
    val selectedAsset = selectApkAsset(AppRelease(tagName, appReleaseAssets))
    return ReleaseProbe.Available(
        identity = ReleaseIdentity(
            channel = channel,
            tagName = tagName,
            releaseId = release.requiredLong("id"),
            assetUpdatedAt = selectedAsset.updatedAt,
        ),
        versionLabel = release.optionalString("name").takeIf { it.isNotBlank() } ?: tagName,
    )
}

internal fun parseReleaseWorkflowStatus(json: String): ReleaseWorkflowStatus {
    val runs = parseJsonObject(json).getValue("workflow_runs").jsonArray
    for (runElement in runs) {
        val status = runElement.jsonObject.optionalString("status")
        if (status == "queued" || status == "in_progress") {
            return ReleaseWorkflowStatus.Running(runElement.jsonObject.requiredLong("id"))
        }
    }
    return ReleaseWorkflowStatus.Idle
}

private val releaseJson = Json { ignoreUnknownKeys = true }

private fun parseJsonObject(json: String): JsonObject =
    releaseJson.parseToJsonElement(json.trimStart('\uFEFF')).jsonObject

private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.optionalString(key: String): String = get(key)?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.requiredLong(key: String): Long = getValue(key).jsonPrimitive.long

private fun JsonObject.optionalLong(key: String): Long? = get(key)?.jsonPrimitive?.longOrNull
