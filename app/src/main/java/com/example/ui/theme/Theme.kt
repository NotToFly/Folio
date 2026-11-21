package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val FolioDarkColorScheme = darkColorScheme(
    primary = CoralPrimary,
    onPrimary = Color(0xFF3E0F05),
    primaryContainer = CoralContainer,
    onPrimaryContainer = OnCoralContainer,
    secondary = MintSage,
    onSecondary = Color(0xFF0A261E),
    secondaryContainer = MintSageContainer,
    onSecondaryContainer = OnMintSageContainer,
    tertiary = AmberTertiary,
    background = ObsidianBackground,
    onBackground = ParchmentInk,
    surface = ObsidianSurface,
    onSurface = ParchmentInk,
    surfaceVariant = ObsidianElevated,
    onSurfaceVariant = ParchmentMuted,
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianElevated,
    surfaceContainerHigh = ObsidianHigh,
    outline = OutlineDark,
    outlineVariant = OutlineDarkSubtle,
    error = ErrorCoral
)

private val FolioLightColorScheme = lightColorScheme(
    primary = TerracottaPrimary,
    onPrimary = Color.White,
    primaryContainer = TerracottaContainer,
    onPrimaryContainer = OnTerracottaContainer,
    secondary = SageSecondary,
    onSecondary = Color.White,
    secondaryContainer = SageContainer,
    onSecondaryContainer = OnSageContainer,
    tertiary = OchreTertiary,
    tertiaryContainer = OchreContainer,
    background = PaperAlabaster,
    onBackground = InkCharcoal,
    surface = PaperPure,
    onSurface = InkCharcoal,
    surfaceVariant = PaperLinen,
    onSurfaceVariant = InkSlate,
    surfaceContainerLow = PaperAlabaster,
    surfaceContainer = PaperLinen,
    surfaceContainerHigh = PaperLinenDeep,
    outline = OutlineWarm,
    outlineVariant = OutlineSubtle,
    error = ErrorCrimson
)

val FolioShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun FolioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FolioDarkColorScheme
        else -> FolioLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FolioTypography,
        shapes = FolioShapes,
        content = content
    )
}
