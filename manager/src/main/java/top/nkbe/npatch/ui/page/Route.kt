package top.nkbe.npatch.ui.page

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation3 路由定义。
 * 包含主页容器 (Main) 和其他全屏页面。
 */
sealed interface Route : NavKey {
    @Serializable
    data class Main(
        val initialTab: Int = MainTab.Home.ordinal,
        val initialManageTab: Int = 0
    ) : Route

    @Serializable data object About : Route

    @Serializable
    data class Welcome(
        val reviewMode: Boolean = false
    ) : Route
    
    @Serializable 
    data class NewPatch(
        val id: Int, 
        val data: String? = null
    ) : Route

    @Serializable 
    data class SelectApps(
        val multiSelect: Boolean, 
        val initialSelected: List<String>? = null
    ) : Route

    @Serializable
    data class RepoDetail(
        val packageName: String
    ) : Route

    @Serializable
    data class RepoScopeFilter(
        val selectedPackageName: String? = null
    ) : Route
}
