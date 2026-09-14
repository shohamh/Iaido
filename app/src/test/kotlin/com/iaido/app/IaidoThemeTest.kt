package com.iaido.app

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IaidoThemeTest {
    @Test
    fun `light and dark palettes are distinct`() {
        assertNotEquals(iaidoLightColors, iaidoDarkColors)
    }

    @Test
    fun `typing settings expose the designed defaults and safe ranges`() {
        assertEquals(2, SettingsDefaults.CASCADE_DEPTH)
        assertEquals(350, SettingsDefaults.GRACE_WINDOW_MS)
        assertEquals(0..4, SettingsDefaults.CASCADE_DEPTH_RANGE)
        assertEquals(300..400, SettingsDefaults.GRACE_WINDOW_RANGE)
    }
}
