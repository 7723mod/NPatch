package org.lsposed.npatch.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.lsposed.manager.ui.compose.repository.RepositoryScreen
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(navigator: Navigator) {
    val pagerState = rememberPagerState(pageCount = { MainTab.entries.size })
    val scope = rememberCoroutineScope()
    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = if (pagerState.currentPage == index) tab.selectedIcon else tab.unselectedIcon,
                        label = tab.label
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .fillMaxSize()
        ) { page ->
            when (MainTab.entries[page]) {
                MainTab.Home -> HomeScreen(navigator)
                MainTab.Manage -> ManageScreen(navigator)
                MainTab.Repo -> RepositoryScreen(navigator)
                MainTab.Settings -> SettingsScreen()
            }
        }
    }
}
