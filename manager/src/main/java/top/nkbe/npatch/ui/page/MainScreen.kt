package top.nkbe.npatch.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import io.github.suqi8.coui.kmp.basic.NavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.lsposed.manager.ui.compose.repository.RepositoryScreen
import top.nkbe.npatch.ui.component.FloatingGlassBottomBar
import top.nkbe.npatch.ui.component.FloatingGlassBottomBarIcon
import top.nkbe.npatch.ui.component.FloatingGlassBottomBarItem
import top.nkbe.npatch.ui.component.FloatingGlassBottomBarLabel
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.LocalFloatingBottomBarPadding
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBar
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBarBlur
import top.nkbe.npatch.ui.util.backgroundAwareCardColors

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    navigator: Navigator,
    selectedTab: Int = MainTab.Home.ordinal,
    onSelectedTabChange: (Int) -> Unit = {},
    selectedManageTab: Int = 0,
    onSelectedManageTabChange: (Int) -> Unit = {},
) {
    val tabs = MainTab.entries
    val safeSelectedTab = selectedTab.coerceIn(0, tabs.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeSelectedTab,
        pageCount = { tabs.size },
    )
    val settledPage by remember(pagerState) {
        derivedStateOf { pagerState.settledPage }
    }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val useFloatingGlassBottomBar = LocalFloatingGlassBottomBar.current
    val useFloatingGlassBottomBarBlur = LocalFloatingGlassBottomBarBlur.current
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val navBarsBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dynamicFloatingBottomBarPadding by remember(density, useFloatingGlassBottomBar, bottomBarHeightPx, navBarsBottomPadding) {
        derivedStateOf {
            if (useFloatingGlassBottomBar && bottomBarHeightPx > 0) {
                with(density) { bottomBarHeightPx.toDp() } + 12.dp + 8.dp + navBarsBottomPadding
            } else if (useFloatingGlassBottomBar) {
                64.dp + 12.dp + 8.dp + navBarsBottomPadding
            } else {
                0.dp
            }
        }
    }
    val surfaceColor = COUITheme.colorScheme.surface
    val backdrop = if (useFloatingGlassBottomBarBlur) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }

    LaunchedEffect(safeSelectedTab) {
        if (pagerState.currentPage != safeSelectedTab) {
            pagerState.scrollToPage(safeSelectedTab)
        }
    }

    LaunchedEffect(navigator, pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect(onSelectedTabChange)
    }

    CompositionLocalProvider(
        LocalFloatingBottomBarPadding provides dynamicFloatingBottomBarPadding
    ) {
        NPatchScaffold(
            bottomBar = {
                if (useFloatingGlassBottomBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                bottom = 12.dp + navBarsBottomPadding,
                            ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        FloatingGlassBottomBar(
                            selectedIndex = { pagerState.currentPage },
                            selectedProgress = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
                            onSelected = { index ->
                                onSelectedTabChange(index)
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            tabsCount = tabs.size,
                            backdrop = backdrop,
                            isBlurEnabled = useFloatingGlassBottomBarBlur,
                            modifier = Modifier
                                .onSizeChanged { bottomBarHeightPx = it.height }
                                .padding(horizontal = 12.dp),
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                val isSelected = settledPage == index
                                val label = stringResource(tab.labelRes)
                                FloatingGlassBottomBarItem(
                                    onClick = {
                                        onSelectedTabChange(index)
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    },
                                    selected = isSelected,
                                    label = label,
                                ) {
                                    FloatingGlassBottomBarIcon(
                                        selected = isSelected,
                                        selectedIcon = tab.selectedIcon,
                                        unselectedIcon = tab.unselectedIcon,
                                    )
                                    FloatingGlassBottomBarLabel(
                                        label = label,
                                        selected = isSelected,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    NavigationBar(color = backgroundAwareCardColors().color) {
                        tabs.forEachIndexed { index, tab ->
                            val isSelected = settledPage == index
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    onSelectedTabChange(index)
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                label = stringResource(tab.labelRes),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .then(
                        if (useFloatingGlassBottomBar &&
                            useFloatingGlassBottomBarBlur &&
                            backdrop != null
                        ) {
                            Modifier.layerBackdrop(backdrop)
                        } else {
                            Modifier
                        },
                    )
                    // Floating glass must sample page content behind itself. Applying Scaffold's
                    // bottom padding here clips the recorded backdrop above the navigation bar.
                    .then(if (useFloatingGlassBottomBar) Modifier else Modifier.padding(padding))
                    .fillMaxSize(),
            ) { page ->
                when (tabs[page]) {
                    MainTab.Home -> HomeScreen(
                        navigator = navigator,
                        onManageShortcut = { managePage ->
                            onSelectedManageTabChange(managePage)
                            onSelectedTabChange(MainTab.Manage.ordinal)
                        },
                    )

                    MainTab.Manage -> ManageScreen(
                        navigator = navigator,
                        selectedPage = selectedManageTab,
                        onSelectedPageChange = onSelectedManageTabChange,
                    )

                    MainTab.Repo -> RepositoryScreen(navigator)
                    MainTab.Settings -> SettingsScreen()
                }
            }
        }
    }
}
