package com.ninjakeys.app

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class NinjaKeysThemeTest {
    @Test
    fun `light and dark palettes are distinct`() {
        assertNotEquals(ninjaKeysLightColors, ninjaKeysDarkColors)
    }
}
