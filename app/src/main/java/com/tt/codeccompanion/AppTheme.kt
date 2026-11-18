package com.tt.codeccompanion

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colors Material's color scheme doesn't have: the green of the active preferred codec, the collapsed card's
 * "Preferred Codec" chip, which has to hold its own among the filled codec chips, and the expanded card's banner; and
 * amber for a device that isn't linked, which needs attention without being an error.
 */
@Immutable
internal data class StatusColors(
    val preferredContainer: Color,
    val onPreferredContainer: Color,
    val attentionContainer: Color,
    val onAttentionContainer: Color,
)

private val lightStatusColors = StatusColors(
    preferredContainer = Color(0xFFC3EFC0),
    onPreferredContainer = Color(0xFF002106),
    attentionContainer = Color(0xFFFFDDB3),
    // 8:1 on the container
    onAttentionContainer = Color(0xFF5C3A00),
)

private val darkStatusColors = StatusColors(
    preferredContainer = Color(0xFF1E5124),
    onPreferredContainer = Color(0xFFB6F2B4),
    attentionContainer = Color(0xFF4A3200),
    onAttentionContainer = Color(0xFFFFDDB3),
)

private val LocalStatusColors = staticCompositionLocalOf { lightStatusColors }

/** The app's colors besides [MaterialTheme.colorScheme], they follow the theme like it. */
internal object AppColors {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

/**
 * The app's palette: Material's tonal spot scheme of the Bluetooth logo's blue, #0082FC, in the 2021 spec that dynamic
 * colors also use on Android 12 to 15. Blue keeps clear of the status colors' green, amber and red. Dynamic colors
 * aren't used, a wallpaper's green or orange would collide with them.
 */
private val lightColors = lightColorScheme(
    primary = Color(0xFF405F90),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF274777),
    inversePrimary = Color(0xFFA9C7FF),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFF6F5575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF9D8FD),
    onTertiaryContainer = Color(0xFF563E5C),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceTint = Color(0xFF405F90),
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF0F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD9D9E0),
)

private val darkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF09305F),
    primaryContainer = Color(0xFF274777),
    onPrimaryContainer = Color(0xFFD6E3FF),
    inversePrimary = Color(0xFF405F90),
    secondary = Color(0xFFBEC7DC),
    onSecondary = Color(0xFF283141),
    secondaryContainer = Color(0xFF3E4759),
    onSecondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFFDCBCE1),
    onTertiary = Color(0xFF3F2845),
    tertiaryContainer = Color(0xFF563E5C),
    onTertiaryContainer = Color(0xFFF9D8FD),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceTint = Color(0xFFA9C7FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2E3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393E),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceDim = Color(0xFF111318),
)

/** The app's Material theme, dark when the system is. */
@Composable
internal fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) darkStatusColors else lightStatusColors) {
        MaterialTheme(colorScheme = if (darkTheme) darkColors else lightColors, content = content)
    }
}
