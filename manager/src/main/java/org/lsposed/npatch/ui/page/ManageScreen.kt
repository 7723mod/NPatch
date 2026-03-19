package org.lsposed.npatch.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import kotlinx.coroutines.launch
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.SearchBar
import org.lsposed.npatch.ui.page.manage.AppManageBody
import org.lsposed.npatch.ui.page.manage.AppManageFab
import org.lsposed.npatch.ui.page.manage.ModuleManageBody
import org.lsposed.npatch.ui.component.SearchBarFake
import org.lsposed.npatch.ui.component.SearchBox
import org.lsposed.npatch.ui.component.SearchPager
import org.lsposed.npatch.ui.component.SearchStatus
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ManageScreen(navigator: Navigator) {
    val scope = rememberCoroutineScope()
    val tabTitles = listOf(stringResource(R.string.apps), stringResource(R.string.modules))

    val pagerState = rememberPagerState(pageCount = { tabTitles.size })
    val scrollBehavior = MiuixScrollBehavior()

    val searchStatus = remember { SearchStatus("搜索") }
    val hazeState = remember { HazeState() }
    val hazeStyle = HazeStyle(
        backgroundColor = MiuixTheme.colorScheme.surface,
        tint = HazeTint(MiuixTheme.colorScheme.surface.copy(0.8f))
    )

    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    Scaffold(
        topBar = {
            searchStatus.TopAppBarAnim(hazeState = hazeState, hazeStyle = hazeStyle) {
                TopAppBar(
                    title = stringResource(R.string.screen_manage),
                    scrollBehavior = scrollBehavior,
                    color = Color.Transparent
                )
            }
        },
        floatingActionButton = {
            if (pagerState.currentPage == 0) AppManageFab(navigator)
        },
        popupHost = {
            searchStatus.SearchPager(
                searchBarTopPadding = dynamicTopPadding,
                expandBar = { status, padding ->
                    Column {
                        SearchBar(status, padding)
                        TabRow(
                            tabs = tabTitles,
                            selectedTabIndex = pagerState.currentPage,
                            onTabSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
                            colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent)
                        )
                    }
                },
                defaultResult = {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val contentPadding = PaddingValues(top = dynamicTopPadding, bottom = 0.dp)
                        when (page) {
                            0 -> AppManageBody(navigator, searchStatus.searchText, contentPadding, scrollBehavior,hazeState)
                            1 -> ModuleManageBody(searchStatus.searchText, contentPadding, scrollBehavior, hazeState)
                        }
                    }
                }
            ) {}
        }
    ) { innerPadding ->
        searchStatus.SearchBox(
            searchBarTopPadding = dynamicTopPadding,
            contentPadding = innerPadding,
            hazeState = hazeState,
            hazeStyle = hazeStyle,
            collapseBar = { status, topPadding, innerPad ->
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    SearchBarFake(status.label, topPadding, innerPad)
                    TabRow(
                        tabs = tabTitles,
                        selectedTabIndex = pagerState.currentPage,
                        onTabSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent)
                    )
                }
            }
        ) { boxHeight ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + boxHeight.value,
                    bottom = innerPadding.calculateBottomPadding()
                )
                when (page) {
                    0 -> AppManageBody(navigator, searchStatus.searchText, contentPadding, scrollBehavior, hazeState)
                    1 -> ModuleManageBody(searchStatus.searchText, contentPadding, scrollBehavior, hazeState)
                }
            }
        }
    }
}
