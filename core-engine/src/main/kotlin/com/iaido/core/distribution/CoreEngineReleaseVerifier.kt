package com.iaido.core.distribution

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class CoreEngineReleaseManifest(
    val version: String,
    val artifact: String,
    val sha256: String,
    val minAppVersion: String,
    val downloadUrl: String,
    val signatureBase64: String,
) {
    /** Canonical bytes signed by the release tool; field order is part of the protocol. */
    fun canonicalPayload(): String = buildString {
        append("artifact=").append(artifact).append('\n')
        append("downloadUrl=").append(downloadUrl).append('\n')
        append("minAppVersion=").append(minAppVersion).append('\n')
        append("sha256=").append(sha256.lowercase()).append('\n')
        append("version=").append(version).append('\n')
    }
}

object CoreEngineReleaseVerifier {
    fun isEligible(manifest: CoreEngineReleaseManifest, installedVersion: SemanticVersion): Boolean = runCatching {
        val releaseVersion = SemanticVersion.parse(manifest.version)
        val minimumVersion = SemanticVersion.parse(manifest.minAppVersion)
        installedVersion >= minimumVersion && releaseVersion.isNewerThan(installedVersion)
    }.getOrDefault(false)

    fun verify(
        manifest: CoreEngineReleaseManifest,
        artifact: ByteArray,
        publicKeyX509: ByteArray,
    ): Boolean = runCatching {
        val expectedHash = manifest.sha256.trim().lowercase()
        if (!SHA256_PATTERN.matches(expectedHash) || expectedHash != sha256Hex(artifact)) return false
        val signature = Base64.getDecoder().decode(manifest.signatureBase64)
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(publicKeyX509))
        Signature.getInstance("Ed25519").run {
            initVerify(publicKey)
            update(manifest.canonicalPayload().toByteArray(Charsets.UTF_8))
            verify(signature)
        }
    }.getOrDefault(false)

    private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
