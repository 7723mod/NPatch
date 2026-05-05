package top.nkbe.npatch.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import org.lsposed.manager.ui.compose.repository.RepositoryScreen
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(navigator: Navigator) {
    val tabs = MainTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    NPatchScaffold(
        bottomBar = {
            NavigationBar(modifier = Modifier.background(backgroundAwareCardColors().color)) {
                tabs.forEachIndexed { index, tab ->
                    val isSelected = pagerState.currentPage == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                        label = stringResource(tab.labelRes)
                    )
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) { page ->
            when (tabs[page]) {
                MainTab.Home -> HomeScreen(navigator)
                MainTab.Manage -> ManageScreen(navigator)
                MainTab.Repo -> RepositoryScreen(navigator)
                MainTab.Settings -> SettingsScreen()
            }
        }
    }
}
