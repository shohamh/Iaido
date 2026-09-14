package com.iaido.core.distribution

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SemanticVersionTest {
    @Test
    fun `only a strictly newer major minor or patch version is eligible`() {
        val installed = SemanticVersion.parse("1.2.3")

        assertTrue(SemanticVersion.parse("1.2.4").isNewerThan(installed))
        assertTrue(SemanticVersion.parse("2.0.0").isNewerThan(installed))
        assertFalse(SemanticVersion.parse("1.2.3").isNewerThan(installed))
        assertFalse(SemanticVersion.parse("1.2.2").isNewerThan(installed))
    }

    @Test
    fun `release is rejected when the installed app is below its minimum version`() {
        val manifest = CoreEngineReleaseManifest(
            version = "2.1.0",
            artifact = "core-engine.jar",
            sha256 = "0".repeat(64),
            minAppVersion = "2.0.0",
            downloadUrl = "https://example.test/core-engine.jar",
            signatureBase64 = "signature",
        )

        assertFalse(CoreEngineReleaseVerifier.isEligible(manifest, SemanticVersion.parse("1.5.0")))
        assertTrue(CoreEngineReleaseVerifier.isEligible(manifest, SemanticVersion.parse("2.0.0")))
    }
}
