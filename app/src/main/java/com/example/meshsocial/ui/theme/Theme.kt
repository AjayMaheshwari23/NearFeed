package com.example.meshsocial.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = LightColors.Accent,
    onPrimary = Color.White,
    background = LightColors.Background,
    onBackground = LightColors.PrimaryText,
    surface = LightColors.Background,
    onSurface = LightColors.PrimaryText,
    surfaceVariant = LightColors.SecondarySurface,
    onSurfaceVariant = LightColors.SecondaryText,
    outline = LightColors.Border,
    outlineVariant = LightColors.Border,
    secondary = LightColors.Success,
    error = LightColors.Error,
    surfaceContainer = LightColors.SecondarySurface,
    surfaceContainerHigh = LightColors.SecondarySurface,
)

private val DarkScheme = darkColorScheme(
    primary = DarkColors.Accent,
    onPrimary = Color.White,
    background = DarkColors.Background,
    onBackground = DarkColors.PrimaryText,
    surface = DarkColors.Background,
    onSurface = DarkColors.PrimaryText,
    surfaceVariant = DarkColors.ElevatedSurface,
    onSurfaceVariant = DarkColors.SecondaryText,
    outline = DarkColors.Border,
    outlineVariant = DarkColors.Border,
    secondary = DarkColors.Success,
    error = DarkColors.Error,
    surfaceContainer = DarkColors.ElevatedSurface,
    surfaceContainerHigh = DarkColors.ElevatedSurface,
)

@Composable
fun NearFeedTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}

/** Semantic success/sync color for both themes. */
val syncGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkColors.Success else LightColors.Success

/** Semantic warning color (scanning/not ready). */
val warnAmber: Color
    @Composable get() = if (isSystemInDarkTheme()) DarkColors.Warning else LightColors.Warning
