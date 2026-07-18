package com.foqos.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Theme color presets shown as circles in Settings. Index 0 is `null` - "use the system default"
 * (dynamic color on API 31+, the static Foqos indigo scheme otherwise). Every other index is a
 * fixed seed color that overrides dynamic color entirely, expanded into a full [androidx.compose.material3.ColorScheme]
 * by [seedColorScheme].
 */
val PRESET_SEED_COLORS: List<Color?> = listOf(
    null,
    FoqosPrimary,
    Color(0xFF1565C0), // blue
    Color(0xFF00897B), // teal
    Color(0xFF2E7D32), // green
    Color(0xFFF9A825), // amber
    Color(0xFFEF6C00), // orange
    Color(0xFFC62828), // red
    Color(0xFFAD1457), // pink
    Color(0xFF6A1B9A), // purple
)
