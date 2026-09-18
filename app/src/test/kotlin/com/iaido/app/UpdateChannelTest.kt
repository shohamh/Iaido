package com.iaido.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateChannelTest {
    @Test
    fun `nightly app defaults to nightly updates`() {
        assertEquals(
            UpdateChannel.NIGHTLY,
            updateChannelFromStoredValue(null, "0.1.12-nightly.42"),
        )
    }

    @Test
    fun `stored channel overrides the installed build default`() {
        assertEquals(
            UpdateChannel.STABLE,
            updateChannelFromStoredValue("stable", "0.1.12-nightly.42"),
        )
        assertEquals(
            UpdateChannel.NIGHTLY,
            updateChannelFromStoredValue("nightly", "0.1.12"),
        )
    }

    @Test
    fun `channels use separate GitHub release endpoints`() {
        assertEquals(
            "https://api.github.com/repos/shohamh/Iaido/releases/latest",
            UpdateChannel.STABLE.apiUrl,
        )
        assertEquals(
            "https://api.github.com/repos/shohamh/Iaido/releases/tags/nightly",
            UpdateChannel.NIGHTLY.apiUrl,
        )
    }
}
