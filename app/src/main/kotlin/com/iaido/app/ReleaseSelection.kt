package com.iaido.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal const val NIGHTLY_RELEASES_API_URL =
    "https://api.github.com/repos/shohamh/Iaido/releases?per_page=100"

private val releaseSelectionJson = Json { ignoreUnknownKeys = true }
private val nightlyTagPattern = Regex("nightly-\\d{12}")

/** Returns the release object to consume, or null when no current nightly exists. */
internal fun selectReleaseJson(json: String, channel: UpdateChannel): String? {
    if (channel != UpdateChannel.NIGHTLY) return json

    return releaseSelectionJson
        .parseToJsonElement(json.trimStart('\uFEFF'))
        .jsonArray
        .asSequence()
        .mapNotNull { it as? JsonObject }
        .filter { release ->
            release.optionalString("tag_name")?.matches(nightlyTagPattern) == true &&
                release.booleanValue("prerelease") &&
                !release.booleanValue("draft")
        }
        .maxByOrNull { release -> release.optionalString("tag_name").orEmpty() }
        ?.toString()
}

private fun JsonObject.booleanValue(key: String): Boolean =
    get(key)?.jsonPrimitive?.booleanOrNull == true

private fun JsonObject.optionalString(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull
