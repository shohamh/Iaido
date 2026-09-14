package com.ninjakeys.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

data class NinjaKeysColors(val background: Long, val surface: Long, val accent: Long)

val ninjaKeysLightColors = NinjaKeysColors(0xFFFFF8F0, 0xFFFFEBDD, 0xFF007F7B)
val ninjaKeysDarkColors = NinjaKeysColors(0xFF201A17, 0xFF3A2F2A, 0xFF61D4CE)

private val lightScheme = lightColorScheme(
    primary = Color(ninjaKeysLightColors.accent),
    background = Color(ninjaKeysLightColors.background),
    surface = Color(ninjaKeysLightColors.surface),
)

private val darkScheme = darkColorScheme(
    primary = Color(ninjaKeysDarkColors.accent),
    background = Color(ninjaKeysDarkColors.background),
    surface = Color(ninjaKeysDarkColors.surface),
)

private val ninjaKeysTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = labelLarge.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
fun NinjaKeysTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme,
        typography = ninjaKeysTypography,
        content = content,
    )
}
