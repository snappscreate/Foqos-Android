package com.foqos.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

private val FoqosLightColorScheme = lightColorScheme(
    primary = FoqosPrimary,
    onPrimary = FoqosOnPrimary,
    primaryContainer = FoqosPrimaryContainer,
    onPrimaryContainer = FoqosOnPrimaryContainer,
    secondary = FoqosSecondary,
    onSecondary = FoqosOnSecondary,
    secondaryContainer = FoqosSecondaryContainer,
    onSecondaryContainer = FoqosOnSecondaryContainer,
    background = FoqosBackgroundLight,
    onBackground = FoqosOnBackgroundLight,
    surface = FoqosSurfaceLight,
    onSurface = FoqosOnSurfaceLight
)

private val FoqosDarkColorScheme = darkColorScheme(
    primary = FoqosPrimaryDark,
    onPrimary = FoqosOnPrimaryDark,
    primaryContainer = FoqosPrimaryContainerDark,
    onPrimaryContainer = FoqosOnPrimaryContainerDark,
    secondary = FoqosSecondaryDark,
    onSecondary = FoqosOnSecondaryDark,
    secondaryContainer = FoqosSecondaryContainerDark,
    onSecondaryContainer = FoqosOnSecondaryContainerDark,
    background = FoqosBackgroundDark,
    onBackground = FoqosOnBackgroundDark,
    surface = FoqosSurfaceDark,
    onSurface = FoqosOnSurfaceDark
)

/**
 * Foqos Material3 theme.
 *
 * @param darkTheme whether to use the dark color scheme, defaults to the system setting.
 * @param dynamicColor whether to use Material You dynamic color on API 31+, enabled by default.
 * Ignored when [seedColor] is non-null.
 * @param seedColor a user-picked theme color (Settings screen) that overrides both dynamic color
 * and the static Foqos scheme, expanded into a full scheme via [seedColorScheme].
 */
@Composable
fun FoqosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    seedColor: Color? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        seedColor != null -> seedColorScheme(seedColor, darkTheme)

        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> FoqosDarkColorScheme
        else -> FoqosLightColorScheme
    }

    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        androidx.compose.runtime.SideEffect {
            activity?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FoqosTypography,
        content = content
    )
}

/**
 * Expands a single user-picked [seed] color into a full [ColorScheme], since this project has no
 * Material color-utilities dependency to generate a real Material You palette from a seed. Uses
 * simple HSV lightness adjustments to derive containers/on-colors - good enough for a handful of
 * preset accent colors, not a substitute for the real dynamic-color algorithm.
 */
fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    val onSeed = if (isDark(seed) || dark) Color.White else Color.Black
    return if (dark) {
        darkColorScheme(
            primary = lighten(seed, 0.15f),
            onPrimary = Color.Black,
            primaryContainer = lighten(seed, -0.30f),
            onPrimaryContainer = lighten(seed, 0.45f),
            secondary = lighten(seed, 0.05f),
            onSecondary = Color.Black,
            secondaryContainer = lighten(seed, -0.20f),
            onSecondaryContainer = lighten(seed, 0.35f),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = onSeed,
            primaryContainer = lighten(seed, 0.35f),
            onPrimaryContainer = lighten(seed, -0.35f),
            secondary = lighten(seed, -0.05f),
            onSecondary = Color.White,
            secondaryContainer = lighten(seed, 0.30f),
            onSecondaryContainer = lighten(seed, -0.30f),
        )
    }
}

private fun isDark(color: Color): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv[2] < 0.5f
}

private fun lighten(color: Color, delta: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[2] = (hsv[2] + delta).coerceIn(0f, 1f)
    if (delta < 0f) hsv[1] = (hsv[1] * 0.9f).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
