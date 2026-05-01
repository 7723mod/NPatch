package org.lsposed.npatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun LSPTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    useMonet: Boolean = false,
    customColor: Int = 0xFF007AFF.toInt(),
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
    MiuixTheme(
        controller = controller,
        content = content
    )
}