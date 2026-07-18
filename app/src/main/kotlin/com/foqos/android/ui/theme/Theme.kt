package com.foqos.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
 */
@Composable
fun FoqosTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
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
