package com.iaido.core.distribution

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreEngineReleaseVerifierTest {
    @Test
    fun `accepts an artifact whose hash and Ed25519 signature match the manifest`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val artifact = "core-engine bytes".toByteArray()
        val unsigned = CoreEngineReleaseManifest(
            version = "0.1.2",
            artifact = "core-engine.jar",
            sha256 = sha256Hex(artifact),
            minAppVersion = "0.1.1",
            downloadUrl = "https://example.test/core-engine.jar",
            signatureBase64 = "",
        )
        val signer = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(unsigned.canonicalPayload().toByteArray())
        }
        val manifest = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))

        assertTrue(CoreEngineReleaseVerifier.verify(manifest, artifact, keyPair.public.encoded))
    }

    @Test
    fun `rejects a changed artifact even when the manifest signature is unchanged`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val artifact = "core-engine bytes".toByteArray()
        val unsigned = CoreEngineReleaseManifest(
            version = "0.1.2",
            artifact = "core-engine.jar",
            sha256 = sha256Hex(artifact),
            minAppVersion = "0.1.1",
            downloadUrl = "https://example.test/core-engine.jar",
            signatureBase64 = "",
        )
        val signer = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(unsigned.canonicalPayload().toByteArray())
        }
        val manifest = unsigned.copy(signatureBase64 = Base64.getEncoder().encodeToString(signer.sign()))

        assertFalse(CoreEngineReleaseVerifier.verify(manifest, "tampered".toByteArray(), keyPair.public.encoded))
    }
}
