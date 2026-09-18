package com.iaido.app

import androidx.compose.foundation.isSystemInDarkTheme
import android.app.UiModeManager
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

data class IaidoColors(val background: Long, val surface: Long, val accent: Long)

val iaidoLightColors = IaidoColors(0xFFF5F3EE, 0xFFFFFCF8, 0xFF176B66)
val iaidoDarkColors = IaidoColors(0xFF121719, 0xFF1B2123, 0xFF7CDED3)

private val lightScheme = lightColorScheme(
    primary = Color(iaidoLightColors.accent),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8E9E2),
    onPrimaryContainer = Color(0xFF00201D),
    secondary = Color(0xFF526461),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8E3),
    onSecondaryContainer = Color(0xFF0E1D1B),
    background = Color(iaidoLightColors.background),
    onBackground = Color(0xFF191C1C),
    surface = Color(iaidoLightColors.surface),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFE0E6E2),
    onSurfaceVariant = Color(0xFF414947),
    outline = Color(0xFF727B78),
    outlineVariant = Color(0xFFC1CAC6),
)

private val darkScheme = darkColorScheme(
    primary = Color(iaidoDarkColors.accent),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF24504B),
    onPrimaryContainer = Color(0xFFB8F2EA),
    secondary = Color(0xFFB7CCC8),
    onSecondary = Color(0xFF223331),
    secondaryContainer = Color(0xFF394B48),
    onSecondaryContainer = Color(0xFFD3E9E4),
    background = Color(iaidoDarkColors.background),
    onBackground = Color(0xFFE1E5E4),
    surface = Color(iaidoDarkColors.surface),
    onSurface = Color(0xFFE1E5E4),
    surfaceVariant = Color(0xFF3B4547),
    onSurfaceVariant = Color(0xFFC0CBC9),
    outline = Color(0xFF899593),
    outlineVariant = Color(0xFF424D4D),
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
    val systemDarkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val uiModeManager = context.getSystemService(UiModeManager::class.java)
    val secureNightMode = Settings.Secure.getInt(
        context.contentResolver,
        "ui_night_mode",
        -1,
    )
    val darkTheme = when {
        secureNightMode == UiModeManager.MODE_NIGHT_YES -> true
        secureNightMode == UiModeManager.MODE_NIGHT_NO -> false
        uiModeManager?.nightMode == UiModeManager.MODE_NIGHT_YES -> true
        uiModeManager?.nightMode == UiModeManager.MODE_NIGHT_NO -> false
        else -> systemDarkTheme
    }
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme else lightScheme,
        typography = iaidoTypography,
        content = content,
    )
}
