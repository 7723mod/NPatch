package top.nkbe.npatch.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
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

val LocalFloatingGlassBottomBar = compositionLocalOf { false }

val LocalFloatingGlassBottomBarBlur = compositionLocalOf { true }

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
): HazeBlurStyle {
    val hasBackground = LocalBackgroundImagePath.current.isNotEmpty()
    return HazeBlurStyle(
        backgroundColor = if (hasBackground) Color.Transparent else surfaceColor,
        colorEffect = HazeColorEffect.tint(surfaceColor.copy(alpha = if (hasBackground) BG_SURFACE_ALPHA else HAZE_TINT_ALPHA))
    )
}
