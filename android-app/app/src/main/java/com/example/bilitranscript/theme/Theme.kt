package com.example.bilitranscript.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanGlow,
    secondary = IceBlue,
    tertiary = LightCyan,
    background = DeepBlue,
    surface = OceanBlue,
    onPrimary = DeepBlue,
    onSecondary = FrostWhite,
    onTertiary = DeepBlue,
    onBackground = FrostWhite,
    onSurface = FrostWhite,
    surfaceVariant = GlassWhite,
    onSurfaceVariant = FrostWhite,
    primaryContainer = IceBlue.copy(alpha = 0.3f),
    onPrimaryContainer = FrostWhite,
    error = Color(0xFFFF6B6B),
    onError = Color.White,
    errorContainer = Color(0x40FF6B6B),
    onErrorContainer = Color(0xFFFF6B6B),
    outline = GlassBorder
)

@Composable
fun BiliTranscriptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
