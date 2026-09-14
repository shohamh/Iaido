package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppUpdateClientTest {
    private val installedCertificates = setOf("old-cert")

    @Test
    fun `accepts a newer APK for the same package and signing certificate`() {
        assertEquals(
            AppArchiveValidation.Valid,
            validateAppArchive(
                archive = AppArchiveInfo("com.iaido.app", 5L, setOf("old-cert"), 10_000L),
                installedPackageName = "com.iaido.app",
                installedVersionCode = 4L,
                installedCertificates = installedCertificates,
            ),
        )
    }

    @Test
    fun `rejects an older or equal version`() {
        val archive = AppArchiveInfo("com.iaido.app", 4L, installedCertificates, 10_000L)

        assertEquals(
            AppArchiveValidation.Invalid("APK is not newer than the installed version"),
            validateAppArchive(archive, "com.iaido.app", 4L, installedCertificates),
        )
    }

    @Test
    fun `rejects wrong package and signing certificate`() {
        assertEquals(
            AppArchiveValidation.Invalid("APK package does not match Iaido"),
            validateAppArchive(
                AppArchiveInfo("com.example.other", 5L, installedCertificates, 10_000L),
                "com.iaido.app",
                4L,
                installedCertificates,
            ),
        )
        assertEquals(
            AppArchiveValidation.Invalid("APK signing certificate does not match the installed app"),
            validateAppArchive(
                AppArchiveInfo("com.iaido.app", 5L, setOf("new-cert"), 10_000L),
                "com.iaido.app",
                4L,
                installedCertificates,
            ),
        )
    }

    @Test
    fun `rejects an oversized APK`() {
        assertEquals(
            AppArchiveValidation.Invalid("APK exceeds the download size limit"),
            validateAppArchive(
                AppArchiveInfo("com.iaido.app", 5L, installedCertificates, 101L),
                "com.iaido.app",
                4L,
                installedCertificates,
                maxBytes = 100L,
            ),
        )
    }
}
