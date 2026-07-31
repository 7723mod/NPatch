package top.nkbe.npatch.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import nkbe.util.ShizukuApi
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.SearchBar
import top.nkbe.npatch.ui.page.manage.AppManageBody
import top.nkbe.npatch.ui.page.manage.AppManageFab
import top.nkbe.npatch.ui.page.manage.ModuleManageBody
import top.nkbe.npatch.ui.component.SearchBarFake
import top.nkbe.npatch.ui.component.SearchBox
import top.nkbe.npatch.ui.component.SearchPager
import top.nkbe.npatch.ui.component.SearchStatus
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBar
import top.nkbe.npatch.ui.util.backgroundAwareHazeStyle
import top.nkbe.npatch.ui.viewmodel.manage.ModuleManageViewModel
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.TabRow
import io.github.suqi8.coui.kmp.basic.TabRowDefaults

@Composable
fun ManageScreen(
    navigator: Navigator,
    selectedPage: Int = 0,
    onSelectedPageChange: (Int) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val tabTitles = listOf(stringResource(R.string.apps), stringResource(R.string.modules))
    val useFloatingGlassBottomBar = LocalFloatingGlassBottomBar.current

    val safeSelectedPage = selectedPage.coerceIn(0, tabTitles.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeSelectedPage,
        pageCount = { tabTitles.size }
    )
    val settledPage by remember(pagerState) {
        derivedStateOf { pagerState.settledPage }
    }
    val scrollBehavior = COUIScrollBehavior()

    val manageSearchLabel = stringResource(R.string.manage_search)
    val searchStatus = remember(manageSearchLabel) { SearchStatus(manageSearchLabel) }
    val moduleManageViewModel = viewModel<ModuleManageViewModel>()
    val hazeState = rememberHazeState()
    val hazeStyle = backgroundAwareHazeStyle()

    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }
    val floatingFabBottomPadding = rememberFloatingBottomBarFabPadding()

    LaunchedEffect(safeSelectedPage) {
        if (pagerState.currentPage != safeSelectedPage) {
            pagerState.scrollToPage(safeSelectedPage)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(onSelectedPageChange)
    }

    LaunchedEffect(
        settledPage,
        ShizukuApi.isReady,
        moduleManageViewModel.enabledActivationPackagesKey
    ) {
        if (settledPage == 1) {
            moduleManageViewModel.refreshScopedActivationState()
            if (ShizukuApi.isReady) {
                moduleManageViewModel.refreshEnabledActivations()
            }
        }
    }

    NPatchScaffold(
        topBar = {
            searchStatus.TopAppBarAnim(hazeState = hazeState, hazeStyle = hazeStyle) {
                NPatchTopAppBar(
                    title = stringResource(R.string.screen_manage),
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (settledPage == 0) {
                AppManageFab(
                    navigator = navigator,
                    modifier = if (useFloatingGlassBottomBar) {
                        Modifier.padding(bottom = floatingFabBottomPadding)
                    } else {
                        Modifier
                    }
                )
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                searchBarTopPadding = dynamicTopPadding,
                expandBar = { status, padding ->
                    Column {
                        SearchBar(status, padding)
                        TabRow(
                            tabs = tabTitles,
                            selectedTabIndex = settledPage,
                            onTabSelected = {
                                onSelectedPageChange(it)
                                scope.launch { pagerState.animateScrollToPage(it) }
                            },
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
                        val contentPadding = PaddingValues(top = dynamicTopPadding + 8.dp, bottom = 0.dp)
                        when (page) {
                            0 -> AppManageBody(navigator, searchStatus.searchText, contentPadding, scrollBehavior,hazeState)
                            1 -> ModuleManageBody(
                                searchStatus.searchText,
                                contentPadding,
                                scrollBehavior,
                                hazeState,
                                moduleManageViewModel
                            )
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
                        selectedTabIndex = settledPage,
                        onTabSelected = {
                            onSelectedPageChange(it)
                            scope.launch { pagerState.animateScrollToPage(it) }
                        },
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
                    top = innerPadding.calculateTopPadding() + boxHeight.value + 8.dp,
                    bottom = innerPadding.calculateBottomPadding()
                )
                when (page) {
                    0 -> AppManageBody(navigator, searchStatus.searchText, contentPadding, scrollBehavior, hazeState)
                    1 -> ModuleManageBody(
                        searchStatus.searchText,
                        contentPadding,
                        scrollBehavior,
                        hazeState,
                        moduleManageViewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberFloatingBottomBarFabPadding() =
    68.dp + 12.dp + 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
