package top.nkbe.npatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.nkbe.npatch.config.DEFAULT_CUSTOM_COLOR
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ThemeController

@Composable
fun LSPTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    useMonet: Boolean = false,
    customColor: Int = DEFAULT_CUSTOM_COLOR,
    content: @Composable () -> Unit
) {
    val controller = remember(isDarkTheme, useMonet, customColor) {
        if (useMonet) {
            ThemeController(ColorSchemeMode.MonetSystem)
        } else {
            ThemeController(
                if (isDarkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
                keyColor = Color(customColor)
            )
        }
    }
    COUITheme(
        controller = controller,
        content = content
    )
}
