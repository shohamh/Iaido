package com.iaido.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import org.json.JSONObject

internal const val MAX_APP_UPDATE_BYTES = 100L * 1024L * 1024L

data class AppArchiveInfo(
    val packageName: String,
    val versionCode: Long,
    val signingCertificates: Set<String>,
    val sizeBytes: Long,
)

sealed interface AppArchiveValidation {
    data object Valid : AppArchiveValidation
    data class Invalid(val reason: String) : AppArchiveValidation
}

fun validateAppArchive(
    archive: AppArchiveInfo,
    installedPackageName: String,
    installedVersionCode: Long,
    installedCertificates: Set<String>,
    maxBytes: Long = MAX_APP_UPDATE_BYTES,
): AppArchiveValidation = when {
    archive.sizeBytes > maxBytes -> AppArchiveValidation.Invalid("APK exceeds the download size limit")
    archive.packageName != installedPackageName -> AppArchiveValidation.Invalid("APK package does not match Iaido")
    archive.versionCode <= installedVersionCode -> AppArchiveValidation.Invalid("APK is not newer than the installed version")
    archive.signingCertificates != installedCertificates ->
        AppArchiveValidation.Invalid("APK signing certificate does not match the installed app")
    else -> AppArchiveValidation.Valid
}

sealed interface AppUpdateResult {
    data object UpToDate : AppUpdateResult
    data class ReadyToInstall(val apk: File, val versionCode: Long) : AppUpdateResult
    data class InstallPermissionRequired(val apk: File, val versionCode: Long) : AppUpdateResult
    data class Failed(val message: String) : AppUpdateResult
}

/** Downloads and validates the latest signed application APK from GitHub Releases. */
class AppUpdateClient(
    private val context: Context,
    private val apiUrl: URL = URL(AppUpdateConfig.RELEASE_API_URL),
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as? HttpURLConnection ?: error("Update URL is not HTTP")
    },
) {
    private val updateDirectory = File(context.filesDir, UPDATE_DIRECTORY)
    private val incomingApk = File(updateDirectory, INCOMING_APK)
    private val stagedApk = File(updateDirectory, STAGED_APK)

    @Synchronized
    fun update(onDownloadStarted: () -> Unit = {}): AppUpdateResult {
        clearIncoming()
        val result = runCatching {
            val release = parseRelease(readText(apiUrl))
            if (release.isDraft || release.isPrerelease) {
                error("Latest GitHub release is not stable")
            }
            val asset = selectApkAsset(release.appRelease)
            if (asset.sizeBytes != null && asset.sizeBytes > MAX_APP_UPDATE_BYTES) {
                error("APK exceeds the download size limit")
            }
            val installed = installedPackageInfo()
            onDownloadStarted()
            download(asset.browserDownloadUrl, incomingApk, asset.sizeBytes)
            val archive = archiveInfo(incomingApk)
            when (val validation = validateAppArchive(
                archive = archive,
                installedPackageName = context.packageName,
                installedVersionCode = installed.longVersionCode,
                installedCertificates = installedCertificates(installed),
            )) {
                AppArchiveValidation.Valid -> {
                    moveReplacing(incomingApk, stagedApk)
                    if (context.packageManager.canRequestPackageInstalls()) {
                        AppUpdateResult.ReadyToInstall(stagedApk, archive.versionCode)
                    } else {
                        AppUpdateResult.InstallPermissionRequired(stagedApk, archive.versionCode)
                    }
                }
                is AppArchiveValidation.Invalid -> if (validation.reason == NOT_NEWER_REASON) {
                    AppUpdateResult.UpToDate
                } else {
                    error(validation.reason)
                }
            }
        }.getOrElse { failure ->
            clearIncoming()
            AppUpdateResult.Failed(failure.message ?: "Could not download the latest Iaido update")
        }
        clearIncoming()
        return result
    }

    fun installIntent(apk: File): Intent {
        require(apk.isFile) { "Staged APK is missing" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun parseRelease(json: String): ParsedRelease {
        val release = JSONObject(json.trimStart('\uFEFF'))
        val assets = release.getJSONArray("assets")
        return ParsedRelease(
            appRelease = AppRelease(
                tagName = release.getString("tag_name"),
                assets = buildList {
                    for (index in 0 until assets.length()) {
                        val asset = assets.getJSONObject(index)
                        add(
                            AppReleaseAsset(
                                name = asset.getString("name"),
                                browserDownloadUrl = asset.getString("browser_download_url"),
                                sizeBytes = asset.optLong("size", -1L).takeIf { it >= 0L },
                            ),
                        )
                    }
                },
            ),
            isDraft = release.optBoolean("draft"),
            isPrerelease = release.optBoolean("prerelease"),
        )
    }

    private fun installedPackageInfo(): PackageInfo = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )

    private fun archiveInfo(file: File): AppArchiveInfo {
        val packageInfo = context.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: error("Downloaded file is not a valid APK")
        return AppArchiveInfo(
            packageName = packageInfo.packageName,
            versionCode = packageInfo.longVersionCode,
            signingCertificates = installedCertificates(packageInfo),
            sizeBytes = file.length(),
        )
    }

    private fun installedCertificates(packageInfo: PackageInfo): Set<String> {
        val signingInfo = packageInfo.signingInfo ?: error("APK has no signing certificate")
        val signatures = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun readText(url: URL): String = readBytes(url, MAX_METADATA_BYTES).toString(Charsets.UTF_8)

    private fun readBytes(url: URL, maxBytes: Long): ByteArray {
        val connection = openConnection(url)
        return try {
            checkResponse(connection)
            val contentLength = connection.contentLengthLong
            if (contentLength > maxBytes) error("Update metadata is too large")
            connection.inputStream.use { stream ->
                stream.readBounded(maxBytes)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(urlValue: String, target: File, expectedSize: Long?) {
        val url = URL(urlValue)
        require(isTrustedAppReleaseUrl(url.toString())) { "APK asset URL is not trusted" }
        val connection = openConnection(url)
        try {
            checkResponse(connection)
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_APP_UPDATE_BYTES) error("APK exceeds the download size limit")
            target.parentFile?.mkdirs()
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APP_UPDATE_BYTES) error("APK exceeds the download size limit")
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (expectedSize != null && total != expectedSize) {
                error("Downloaded APK size does not match the GitHub asset")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: URL): HttpURLConnection = connectionFactory(url).apply {
        require(url.protocol.equals("https", ignoreCase = true)) { "Update URLs must use HTTPS" }
        connectTimeout = 5_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "Iaido-App-Updater")
    }

    private fun checkResponse(connection: HttpURLConnection) {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            error("Update request failed: ${connection.responseCode}")
        }
    }

    private fun clearIncoming() {
        if (incomingApk.exists()) incomingApk.delete()
    }

    private fun moveReplacing(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private data class ParsedRelease(
        val appRelease: AppRelease,
        val isDraft: Boolean,
        val isPrerelease: Boolean,
    )

    private companion object {
        const val UPDATE_DIRECTORY = "app-updates"
        const val INCOMING_APK = "app-update.apk.incoming"
        const val STAGED_APK = "app-update.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val MAX_METADATA_BYTES = 2L * 1024L * 1024L
        const val NOT_NEWER_REASON = "APK is not newer than the installed version"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private fun java.io.InputStream.readBounded(maxBytes: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) error("Update response is too large")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private const val APP_UPDATE_API_URL = "https://api.github.com/repos/shohamh/NinjaKeys/releases/latest"

object AppUpdateConfig {
    const val RELEASE_API_URL = APP_UPDATE_API_URL
}
