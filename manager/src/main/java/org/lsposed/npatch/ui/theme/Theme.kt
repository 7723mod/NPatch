package org.lsposed.npatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun LSPTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val controller = if (isDarkTheme) {
        ThemeController(ColorSchemeMode.Dark)
    } else {
        ThemeController(ColorSchemeMode.Light)
    }
    MiuixTheme(
        controller = controller,
        content = content
    )
}