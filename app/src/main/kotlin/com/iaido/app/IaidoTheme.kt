package com.iaido.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

data class IaidoColors(val background: Long, val surface: Long, val accent: Long)

val iaidoLightColors = IaidoColors(0xFFFFF8F0, 0xFFFFEBDD, 0xFF007F7B)
val iaidoDarkColors = IaidoColors(0xFF201A17, 0xFF3A2F2A, 0xFF61D4CE)

private val lightScheme = lightColorScheme(
    primary = Color(iaidoLightColors.accent),
    background = Color(iaidoLightColors.background),
    surface = Color(iaidoLightColors.surface),
)

private val darkScheme = darkColorScheme(
    primary = Color(iaidoDarkColors.accent),
    background = Color(iaidoDarkColors.background),
    surface = Color(iaidoDarkColors.surface),
)

private val iaidoTypography = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontFamily = FontFamily.SansSerif),
        bodyMedium = bodyMedium.copy(fontFamily = FontFamily.SansSerif),
        labelLarge = labelLarge.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
fun IaidoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkScheme else lightScheme,
        typography = iaidoTypography,
        content = content,
    )
}
