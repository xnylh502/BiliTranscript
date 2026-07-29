package com.example.bilitranscript.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

// =============================================================================
// Claude-style Light Color Scheme
// Maps Claude design tokens → Material3 color roles
// =============================================================================

private val ClaudeColorScheme = lightColorScheme(
    // Primary = Claude's signature warm orange
    primary = ClaudeAccent,
    onPrimary = ClaudeTextOnAccent,
    primaryContainer = ClaudeAccentUltraLight,
    onPrimaryContainer = ClaudeAccent,

    // Secondary = neutral warm gray
    secondary = ClaudeTextSecondary,
    onSecondary = ClaudeSurface,
    secondaryContainer = ClaudeSurfaceHover,
    onSecondaryContainer = ClaudeTextPrimary,

    // Tertiary = info blue for links etc.
    tertiary = ClaudeInfo,
    onTertiary = ClaudeSurface,
    tertiaryContainer = Color(0xFFDBEAFE),
    onTertiaryContainer = ClaudeInfo,

    // Error
    error = ClaudeError,
    onError = ClaudeTextOnAccent,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = ClaudeError,

    // Backgrounds
    background = ClaudeBackground,
    onBackground = ClaudeTextPrimary,

    // Surfaces
    surface = ClaudeSurface,
    onSurface = ClaudeTextPrimary,
    surfaceVariant = ClaudeSurfaceHover,
    onSurfaceVariant = ClaudeTextSecondary,

    // Outlines & borders
    outline = ClaudeBorder,
    outlineVariant = ClaudeInputBorder,
)

// =============================================================================
// CompositionLocals for extra design tokens beyond Material3
// =============================================================================

/**
 * Extra Claude-specific design values that don't map neatly into Material3 roles.
 * Access via `LocalClaudeTokens.current` in any @Composable.
 */
@Immutable
data class ClaudeTokens(
    /** Background of the entire app shell */
    val appBackground: Color = ClaudeBackground,
    /** Card / content area background */
    val cardBackground: Color = ClaudeSurface,
    /** Input field border (unfocused) */
    val inputBorder: Color = ClaudeInputBorder,
    /** Input field border (focused) */
    val inputFocusBorder: Color = ClaudeInputFocusBorder,
    /** Input field background */
    val inputBackground: Color = ClaudeSurface,
    /** Divider line color */
    val divider: Color = ClaudeBorder,
    /** Navigation bar background */
    val navBarBackground: Color = ClaudeSurface,
    /** Bottom sheet / modal scrim */
    val scrim: Color = ClaudeScrim,
    /** Accent for interactive elements (links, highlights) */
    val accent: Color = ClaudeAccent,
    /** Success state */
    val success: Color = ClaudeSuccess,
    /** Warning state */
    val warning: Color = ClaudeWarning,
    /** Progress bar track */
    val progressTrack: Color = ClaudeProgressTrack,
    /** Progress bar fill */
    val progressFill: Color = ClaudeProgressFill,
    /** Standard corner radius for cards */
    val cardRadius: ClaudeRadius = ClaudeRadius.Medium,
    /** Standard corner radius for buttons */
    val buttonRadius: ClaudeRadius = ClaudeRadius.Full,
    /** Standard corner radius for input fields */
    val inputRadius: ClaudeRadius = ClaudeRadius.Medium,
    /** Elevation for bottom nav bar */
    val navBarElevation: Float = 0f,
    /** Elevation for cards */
    val cardElevation: Float = 0f,
    /** Elevation for dialogs */
    val dialogElevation: Float = 8f,
)

/** Predefined radius tokens matching Claude's design language */
@Immutable
sealed class ClaudeRadius(val value: Dp) {
    data object None : ClaudeRadius(0.dp)
    data object Small : ClaudeRadius(8.dp)
    data object Medium : ClaudeRadius(12.dp)
    data object Large : ClaudeRadius(16.dp)
    data object XLarge : ClaudeRadius(20.dp)
    data object Full : ClaudeRadius(50.dp)
}

val LocalClaudeTokens = staticCompositionLocalOf { ClaudeTokens() }

// =============================================================================
// Theme composable
// =============================================================================

@Composable
fun BiliTranscriptTheme(content: @Composable () -> Unit) {
    // Claude mobile is light-only (no dark mode support currently)
    // We respect system dark-theme setting by keeping light scheme —
    // dark mode can be added later if needed.
    val colorScheme = ClaudeColorScheme

    CompositionLocalProvider(LocalClaudeTokens provides ClaudeTokens()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ClaudeTypography,
            content = content
        )
    }
}
