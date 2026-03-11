package org.lsposed.npatch.ui.page

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主页面底部导航标签枚举。
 */
enum class MainTab(val label: String, val selectedIcon: ImageVector, val unselectedIcon: ImageVector) {
    Home("首页", Icons.Filled.Home, Icons.Outlined.Home),
    Manage("管理", Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
    Repo("仓库", Icons.Filled.GetApp, Icons.Outlined.GetApp),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings)
}
