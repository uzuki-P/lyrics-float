package dev.lyricsfloat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

enum class ThemeMode { LIGHT, DARK, SYSTEM }

data class AppPalette(
    val surface: Color,
    val onSurface: Color,
    val accent: Color,
    val onAccent: Color,
    /** Overlay text keeps a heavy shadow so it reads on any wallpaper. */
    val textShadow: Color,
) {
    val divider: Color = onSurface.copy(alpha = 0.08f)
    val onSurfaceDim: Color = onSurface.copy(alpha = 0.55f)
}

internal val DarkPalette = AppPalette(
    surface = Color(0xFF16162C),
    onSurface = Color.White,
    accent = Color(0xFFFF8CC6),
    onAccent = Color(0xFF2B1220),
    textShadow = Color(0xB3000000),
)

internal val LightPalette = AppPalette(
    surface = Color(0xFFF7F3FA),
    onSurface = Color(0xFF261B2F),
    accent = Color(0xFFD9558F),
    onAccent = Color.White,
    textShadow = Color(0x66000000),
)

val LocalAppPalette = staticCompositionLocalOf<AppPalette> { DarkPalette }

fun themeIsDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

// Compose's isSystemInDarkTheme() reads a composition-local default that is
// resolved once per process on desktop, so it never tracks theme changes.
// skiko's query is a cheap XDG-portal DBus read, so polling is enough.
fun systemThemeIsDark(): Boolean =
    org.jetbrains.skiko.currentSystemTheme == org.jetbrains.skiko.SystemTheme.DARK

@Composable
fun rememberSystemThemeIsDark(pollMillis: Long = 1_000L): State<Boolean> =
    produceState(systemThemeIsDark()) {
        while (isActive) {
            value = systemThemeIsDark()
            delay(pollMillis)
        }
    }
