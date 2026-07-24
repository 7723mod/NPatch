package top.nkbe.npatch.ui.page

import top.nkbe.npatch.R
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Sidebar
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud

/**
 * 主页面底部导航标签枚举。
 */
enum class MainTab(
    @param:StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    Home(R.string.screen_home, MiuixIcons.Regular.Sidebar, MiuixIcons.Light.Sidebar),
    Manage(R.string.screen_manage, MiuixIcons.Regular.Tune, MiuixIcons.Light.Tune),
    Repo(R.string.screen_repo, MiuixIcons.Regular.UploadCloud, MiuixIcons.Light.UploadCloud),
    Settings(R.string.screen_settings, MiuixIcons.Regular.Settings, MiuixIcons.Light.Settings)
}
