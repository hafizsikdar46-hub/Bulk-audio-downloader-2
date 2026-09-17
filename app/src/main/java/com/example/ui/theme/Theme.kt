package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    onPrimary = BlackBackground,
    primaryContainer = DarkSurfaceElevated,
    onPrimaryContainer = CyanAccent,
    secondary = CyanAccentMuted,
    onSecondary = BlackBackground,
    background = BlackBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkSurfaceElevated,
    error = RedError,
    onError = BlackBackground
)

@Composable
fun MediaDownloaderTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
