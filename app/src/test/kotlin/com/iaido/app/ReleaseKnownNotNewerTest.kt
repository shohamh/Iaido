package com.iaido.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class ReleaseKnownNotNewerTest {
    @Test
    fun sameStableVersionIsNotNewer() = assertTrue(isReleaseKnownNotNewer("v0.1.20", "0.1.20"))

    @Test
    fun newerTagIsDownloaded() = assertFalse(isReleaseKnownNotNewer("v0.1.21", "0.1.20"))

    @Test
    fun olderTagIsNotNewer() = assertTrue(isReleaseKnownNotNewer("v0.1.19", "0.1.20-nightly.5"))

    @Test
    fun sameBaseAsNightlyIsNotNewer() = assertTrue(isReleaseKnownNotNewer("v0.1.20", "0.1.20-nightly.5"))

    @Test
    fun unknownTagStillDownloads() = assertFalse(isReleaseKnownNotNewer("latest", "0.1.20"))
}
