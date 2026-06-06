package top.nkbe.npatch.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import top.yukonga.miuix.kmp.basic.Text
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
    val settledPage by remember(pagerState) {
        derivedStateOf { pagerState.settledPage }
    }
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
        snapshotFlow { pagerState.settledPage }
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
                            val isSelected = settledPage == index
                            val label = stringResource(tab.labelRes)
                            FloatingGlassBottomBarItem(
                                onClick = {
                                    onSelectedTabChange(index)
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                },
                                selected = isSelected,
                                label = label
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
                        val isSelected = settledPage == index
                        val label = stringResource(tab.labelRes)
                        MainNavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                onSelectedTabChange(index)
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            icon = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                            label = label
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

@Composable
private fun RowScope.MainNavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> if (selected) {
            onSurfaceContainerColor.copy(alpha = 0.5f)
        } else {
            onSurfaceContainerColor.copy(alpha = 0.6f)
        }

        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.4f)
    }
    val fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal

    Column(
        modifier = Modifier
            .height(64.dp)
            .weight(1f)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Image(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(26.dp),
            imageVector = icon,
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
        )
        Text(
            modifier = Modifier.padding(bottom = 8.dp),
            text = label,
            color = tint,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            fontWeight = fontWeight,
        )
    }
}
