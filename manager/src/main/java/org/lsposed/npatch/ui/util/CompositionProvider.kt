package org.lsposed.npatch.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme

const val BG_SURFACE_ALPHA = 0.6f
private const val BG_OVERLAY_ALPHA = 0.35f
private const val HAZE_TINT_ALPHA = 0.8f

val LocalSnackbarHost = compositionLocalOf<SnackbarHostState> {
    error("CompositionLocal LocalSnackbarController not present")
}

val LocalBackgroundImagePath = compositionLocalOf { "" }

@Composable
fun backgroundAwareCardColors(
    color: Color = MiuixTheme.colorScheme.surface,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
): CardColors {
    val adjusted = if (LocalBackgroundImagePath.current.isNotEmpty()) {
        color.copy(alpha = BG_SURFACE_ALPHA)
    } else {
        color
    }
    return CardDefaults.defaultColors(
        color = adjusted,
        contentColor = contentColor,
    )
}

@Composable
fun backgroundAwareHazeStyle(
    surfaceColor: Color = MiuixTheme.colorScheme.surface,
): HazeStyle {
    val hasBackground = LocalBackgroundImagePath.current.isNotEmpty()
    return HazeStyle(
        backgroundColor = if (hasBackground) Color.Transparent else surfaceColor,
        tint = HazeTint(surfaceColor.copy(alpha = if (hasBackground) BG_SURFACE_ALPHA else HAZE_TINT_ALPHA))
    )
}

