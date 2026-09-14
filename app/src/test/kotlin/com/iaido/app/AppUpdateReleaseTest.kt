package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AppUpdateReleaseTest {
    @Test
    fun `selects a GitHub release APK`() {
        val release = AppRelease(
            tagName = "v0.1.4",
            assets = listOf(
                AppReleaseAsset("checksums.txt", "https://github.com/shohamh/Iaido/releases/download/v0.1.4/checksums.txt", 12),
                AppReleaseAsset("app-release.apk", "https://github.com/shohamh/Iaido/releases/download/v0.1.4/app-release.apk", 1234),
            ),
        )

        assertEquals("app-release.apk", selectApkAsset(release).name)
        assertEquals(1234L, selectApkAsset(release).sizeBytes)
    }

    @Test
    fun `rejects a release without an APK`() {
        assertThrows(IllegalArgumentException::class.java) {
            selectApkAsset(AppRelease("v0.1.4", listOf()))
        }
    }

    @Test
    fun `rejects multiple APK assets`() {
        val assets = listOf(
            AppReleaseAsset("app-release.apk", "https://github.com/shohamh/Iaido/releases/download/v0.1.4/a.apk", null),
            AppReleaseAsset("app-debug.apk", "https://github.com/shohamh/Iaido/releases/download/v0.1.4/b.apk", null),
        )

        assertThrows(IllegalArgumentException::class.java) {
            selectApkAsset(AppRelease("v0.1.4", assets))
        }
    }

    @Test
    fun `rejects APK URLs outside the fixed GitHub release path`() {
        val release = AppRelease(
            "v0.1.4",
            listOf(AppReleaseAsset("app-release.apk", "https://example.com/app-release.apk", null)),
        )

        assertThrows(IllegalArgumentException::class.java) { selectApkAsset(release) }
    }
}
