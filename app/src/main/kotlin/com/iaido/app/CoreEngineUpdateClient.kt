package com.iaido.app

import android.content.Context
import com.iaido.core.distribution.CoreEngineReleaseManifest
import com.iaido.core.distribution.CoreEngineReleaseVerifier
import com.iaido.core.distribution.SemanticVersion
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import org.json.JSONObject

private const val MAX_ARTIFACT_BYTES = 20 * 1024 * 1024
private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L

internal fun parseInstalledAppVersion(versionName: String?): SemanticVersion? =
    versionName
        ?.substringBefore('-')
        ?.takeIf { it.isNotBlank() }
        ?.let { value -> runCatching { SemanticVersion.parse(value) }.getOrNull() }

/** Fetches, verifies, and stages a newer signed core-engine release. */
class CoreEngineUpdateClient(
    private val context: Context,
    private val manifestUrl: URL = URL(CoreEngineUpdateConfig.MANIFEST_URL),
    private val installedVersion: SemanticVersion? = parseInstalledAppVersion(
        context.packageManager.getPackageInfo(context.packageName, 0).versionName,
    ),
    private val store: CoreEngineUpdateStore = CoreEngineUpdateStore(
        FilePaths.coreEngineUpdateDirectory(context),
    ),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun checkAndInstall(): Boolean {
        val now = System.currentTimeMillis()
        val lastCheck = preferences.getLong(LAST_CHECK_KEY, 0L)
        if (now - lastCheck < UPDATE_CHECK_INTERVAL_MS) return false
        preferences.edit().putLong(LAST_CHECK_KEY, now).apply()
        return runCatching {
        val manifest = parseManifest(readText(manifestUrl))
        val currentVersion = store.currentVersion()
            ?.let { value -> runCatching { SemanticVersion.parse(value) }.getOrNull() }
            ?: installedVersion
        if (currentVersion == null) return@runCatching false
        if (manifest.artifact != "core-engine.jar") return false
        if (!CoreEngineReleaseVerifier.isEligible(manifest, currentVersion)) return false

        val artifactUrl = URL(manifest.downloadUrl)
        require(artifactUrl.protocol == "https") { "Core-engine artifact URL must use HTTPS" }
        val artifact = readBytes(artifactUrl)
        if (!CoreEngineReleaseVerifier.verify(manifest, artifact, embeddedPublicKey())) return false
        store.installVerifiedArtifact(manifest.version, artifact)
        true
        }.getOrDefault(false)
    }

    private fun parseManifest(json: String): CoreEngineReleaseManifest {
        val objectValue = JSONObject(json.trimStart('\uFEFF'))
        return CoreEngineReleaseManifest(
            version = objectValue.getString("version"),
            artifact = objectValue.getString("artifact"),
            sha256 = objectValue.getString("sha256"),
            minAppVersion = objectValue.getString("minAppVersion"),
            downloadUrl = objectValue.getString("downloadUrl"),
            signatureBase64 = objectValue.getString("signature"),
        )
    }

    private fun embeddedPublicKey(): ByteArray = context.resources.openRawResource(R.raw.core_engine_update_public_key)
        .bufferedReader()
        .use { reader ->
            Base64.getDecoder().decode(
                reader.readText().lineSequence().filterNot { it.startsWith("---") }.joinToString(""),
            )
        }

    private fun readText(url: URL): String = readBytes(url).toString(Charsets.UTF_8)

    private fun readBytes(url: URL): ByteArray {
        require(url.protocol == "https") { "Update URL must use HTTPS" }
        val connection = url.openConnection() as? HttpURLConnection
            ?: error("Update URL did not create an HTTP connection")
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("Update request failed: ${connection.responseCode}")
            }
            connection.inputStream.use { stream ->
                val bytes = stream.readNBytes(MAX_ARTIFACT_BYTES + 1)
                require(bytes.size <= MAX_ARTIFACT_BYTES) { "Update artifact is too large" }
                bytes
            }
        } finally {
            connection.disconnect()
        }
    }
}

object CoreEngineUpdateConfig {
    const val MANIFEST_URL =
        "https://github.com/shohamh/Iaido/releases/latest/download/core-engine-manifest.json"
}

private const val PREFERENCES_NAME = "core-engine-updates"
private const val LAST_CHECK_KEY = "last-check-ms"

private object FilePaths {
    fun coreEngineUpdateDirectory(context: Context) = java.io.File(context.filesDir, "core-engine-updates")
}
