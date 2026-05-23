package top.nkbe.npatch.ui.page

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.lsposed.manager.ui.compose.repository.RepositoryScreen
import top.nkbe.npatch.ui.component.FloatingGlassBottomBar
import top.nkbe.npatch.ui.component.FloatingGlassBottomBarIcon
import top.nkbe.npatch.ui.component.FloatingGlassBottomBarItem
import top.nkbe.npatch.ui.component.FloatingGlassBottomBarLabel
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBar
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBarBlur
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    navigator: Navigator,
    selectedTab: Int = MainTab.Home.ordinal,
    selectedManageTab: Int = 0,
    onSelectedTabChange: (Int) -> Unit = {},
    onSelectedManageTabChange: (Int) -> Unit = {},
) {
    val tabs = MainTab.entries
    val safeSelectedTab = selectedTab.coerceIn(0, tabs.lastIndex)
    val pagerState = rememberPagerState(
        initialPage = safeSelectedTab,
        pageCount = { tabs.size }
    )
    val scope = rememberCoroutineScope()
    val useFloatingGlassBottomBar = LocalFloatingGlassBottomBar.current
    val useFloatingGlassBottomBarBlur = LocalFloatingGlassBottomBarBlur.current
    val surfaceColor = MiuixTheme.colorScheme.surface
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
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect(onSelectedTabChange)
    }

    NPatchScaffold(
        bottomBar = {
            if (useFloatingGlassBottomBar) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            bottom = 12.dp + WindowInsets.navigationBars
                                .asPaddingValues()
                                .calculateBottomPadding()
                        ),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    FloatingGlassBottomBar(
                        selectedIndex = { pagerState.currentPage },
                        onSelected = { index ->
                            onSelectedTabChange(index)
                            scope.launch { pagerState.animateScrollToPage(index) }
                        },
                        tabsCount = tabs.size,
                        backdrop = backdrop,
                        isBlurEnabled = useFloatingGlassBottomBarBlur,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            val isSelected = pagerState.currentPage == index
                            val label = stringResource(tab.labelRes)
                            FloatingGlassBottomBarItem(
                                onClick = {
                                    onSelectedTabChange(index)
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                            ) {
                                FloatingGlassBottomBarIcon(
                                    selected = isSelected,
                                    selectedIcon = tab.selectedIcon,
                                    unselectedIcon = tab.unselectedIcon
                                )
                                FloatingGlassBottomBarLabel(label)
                            }
                        }
                    }
                }
            } else {
                NavigationBar(modifier = Modifier.background(backgroundAwareCardColors().color)) {
                    tabs.forEachIndexed { index, tab ->
                        val isSelected = pagerState.currentPage == index
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                onSelectedTabChange(index)
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
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .then(
                    if (useFloatingGlassBottomBar && useFloatingGlassBottomBarBlur && backdrop != null) {
                        Modifier.layerBackdrop(backdrop)
                    } else {
                        Modifier
                    }
                )
                .then(if (useFloatingGlassBottomBar) Modifier else Modifier.padding(padding))
                .fillMaxSize(),
        ) { page ->
            when (tabs[page]) {
                MainTab.Home -> HomeScreen(
                    navigator = navigator,
                    onManageShortcut = { managePage ->
                        onSelectedManageTabChange(managePage)
                        onSelectedTabChange(MainTab.Manage.ordinal)
                    }
                )
                MainTab.Manage -> ManageScreen(
                    navigator = navigator,
                    selectedPage = selectedManageTab,
                    onSelectedPageChange = onSelectedManageTabChange
                )
                MainTab.Repo -> RepositoryScreen(navigator)
                MainTab.Settings -> SettingsScreen()
            }
        }
    }
}
