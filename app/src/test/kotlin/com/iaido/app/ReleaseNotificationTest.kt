package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseNotificationTest {
    @Test
    fun `notification copy includes channel and version`() {
        assertEquals("Iaido Nightly update available", releaseNotificationTitle(UpdateChannel.NIGHTLY))
        assertEquals("Version 0.1.14-nightly.18 is ready to install", releaseNotificationText("0.1.14-nightly.18"))
    }

    @Test
    fun `stable notification uses stable wording`() {
        assertEquals("Iaido Stable release update available", releaseNotificationTitle(UpdateChannel.STABLE))
    }
}
