package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val OnyxDarkColorScheme = darkColorScheme(
    primary = OnyxAccentBlue,
    onPrimary = OnyxOnAccent,
    primaryContainer = OnyxSelectedBg,
    onPrimaryContainer = OnyxAccentBlue,
    secondary = OnyxFabBg,
    onSecondary = OnyxFabInk,
    secondaryContainer = OnyxBadgeBg,
    onSecondaryContainer = OnyxTextPrimary,
    tertiary = OnyxSuccess,
    background = OnyxBlack,
    onBackground = OnyxTextPrimary,
    surface = OnyxTopBar,
    onSurface = OnyxTextPrimary,
    surfaceVariant = OnyxBadgeBg,
    onSurfaceVariant = OnyxTextMuted,
    surfaceContainerLow = OnyxCardSurface,
    surfaceContainer = OnyxSurface,
    surfaceContainerHigh = OnyxElevated,
    outline = OnyxBorder,
    outlineVariant = OnyxHairline,
    error = OnyxError,
    onError = OnyxOnError
)

val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun FolioTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = OnyxDarkColorScheme,
        typography = FolioTypography,
        shapes = FolioShapes,
        content = content
    )
}
