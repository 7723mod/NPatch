package top.nkbe.npatch.ui.page

import android.content.pm.ApplicationInfo
import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Done
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toLowerCase
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.parcelize.Parcelize
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.AppItem
import top.nkbe.npatch.ui.component.SearchBar
import top.nkbe.npatch.ui.component.SearchBarFake
import top.nkbe.npatch.ui.component.SearchBox
import top.nkbe.npatch.ui.component.SearchPager
import top.nkbe.npatch.ui.component.SearchStatus
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchPullToRefresh
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.util.backgroundAwareHazeStyle
import top.nkbe.npatch.ui.viewmodel.SelectAppsViewModel
import androidx.compose.ui.state.ToggleableState
import io.github.suqi8.coui.kmp.basic.Checkbox
import dev.chrisbanes.haze.rememberHazeState
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.rememberPullToRefreshState
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

@Parcelize
sealed class SelectAppsResult : Parcelable {
    data class SingleApp(val selected: AppInfo) : SelectAppsResult()
    data class MultipleApps(val selected: List<AppInfo>) : SelectAppsResult()
}

@Composable
fun SelectAppsScreen(
    multiSelect: Boolean,
    initialSelected: List<String>?,
) {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<SelectAppsViewModel>()

    var searchPackage by remember { mutableStateOf("") }
    val filter: (AppInfo) -> Boolean = remember(multiSelect, searchPackage) {
        {
            val packageLowerCase = searchPackage.toLowerCase(Locale.current)
            val contains = it.label.toLowerCase(Locale.current).contains(packageLowerCase) || it.app.packageName.contains(packageLowerCase)
            if (multiSelect) contains && it.isXposedModule
            else contains && it.app.flags and ApplicationInfo.FLAG_SYSTEM == 0
        }
    }

    val title = if (multiSelect) stringResource(R.string.screen_select_modules) else stringResource(R.string.screen_select_apps)
    val searchStatus = remember(multiSelect, title) { SearchStatus(title) }
    val hazeState = rememberHazeState()
    val hazeStyle = backgroundAwareHazeStyle()

    val scrollBehavior = COUIScrollBehavior()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    LaunchedEffect(multiSelect, initialSelected) {
        viewModel.multiSelected.clear()
        viewModel.filterAppList(false, filter)
        initialSelected?.let {
            val tmp = initialSelected.toSet()
            viewModel.multiSelected.addAll(NeoPackageManager.appList.filter { tmp.contains(it.app.packageName) })
        }
    }

    LaunchedEffect(searchStatus.searchText) {
        searchPackage = searchStatus.searchText
        viewModel.filterAppList(false, filter)
    }

    BackHandler {
        navigator.pop()
    }

    NPatchScaffold(
        topBar = {
            searchStatus.TopAppBarAnim {
                NPatchTopAppBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    hazeState = hazeState,
                    hazeStyle = hazeStyle,
                    navigationIcon = {
                        IconButton(
                            onClick = { navigator.pop() }
                        ) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = COUIIcons.Back,
                                contentDescription = null,
                                tint = COUITheme.colorScheme.onSurface
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (multiSelect) MultiSelectFab {
                navigator.setResultAndBack(SelectAppsResult.MultipleApps(viewModel.multiSelected.toList()))
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                searchBarTopPadding = dynamicTopPadding,
                expandBar = { status, padding ->
                    SearchBar(status, padding)
                },
                defaultResult = {
                    val contentPadding = PaddingValues(top = dynamicTopPadding, bottom = 0.dp)
                    SelectAppsList(
                        multiSelect = multiSelect,
                        viewModel = viewModel,
                        contentPadding = contentPadding,
                        hazeState = hazeState,
                        onRefresh = { viewModel.filterAppList(true, filter) },
                        onSingleSelect = {
                            navigator.setResultAndBack(SelectAppsResult.SingleApp(it))
                        }
                    )
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
                SearchBarFake(
                    label = status.label,
                    searchBarTopPadding = topPadding,
                    innerPadding = innerPad,
                    onClick = { status.current = SearchStatus.Status.EXPANDING }
                )
            }
        ) { boxHeight ->
            val contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + boxHeight.value,
                bottom = innerPadding.calculateBottomPadding()
            )
            SelectAppsList(
                multiSelect = multiSelect,
                viewModel = viewModel,
                contentPadding = contentPadding,
                hazeState = hazeState,
                onRefresh = { viewModel.filterAppList(true, filter) },
                onSingleSelect = {
                    navigator.setResultAndBack(SelectAppsResult.SingleApp(it))
                }
            )
        }
    }
}

@Composable
private fun MultiSelectFab(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
    ) {
        Icon(
            imageVector = Icons.Outlined.Done,
            contentDescription = stringResource(R.string.add),
            tint = COUITheme.colorScheme.onPrimary
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectAppsList(
    multiSelect: Boolean,
    viewModel: SelectAppsViewModel,
    contentPadding: PaddingValues,
    hazeState: HazeState,
    onRefresh: () -> Unit,
    onSingleSelect: (AppInfo) -> Unit
) {
    val pullToRefreshState = rememberPullToRefreshState()
    NPatchPullToRefresh(
        isRefreshing = viewModel.isRefreshing,
        pullToRefreshState = pullToRefreshState,
        onRefresh = onRefresh,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .hazeSource(state = hazeState),
            contentPadding = contentPadding,
            overscrollEffect = null
        ) {
            items(
                items = viewModel.filteredList,
                key = { it.app.packageName }
            ) { appInfo ->
                val checked = if (multiSelect) {
                    viewModel.multiSelected.any { it.app.packageName == appInfo.app.packageName }
                } else {
                    false
                }

                AppItem(
                    modifier = Modifier.animateItem(spring(stiffness = Spring.StiffnessLow)),
                    onClick = {
                        if (multiSelect) {
                            if (checked) viewModel.multiSelected.removeAll { it.app.packageName == appInfo.app.packageName }
                            else viewModel.multiSelected.add(appInfo)
                        } else {
                            onSingleSelect(appInfo)
                        }
                    },
                    icon = {
                        Image(
                            bitmap = NeoPackageManager.getIcon(appInfo),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                        )
                    },
                    label = appInfo.label,
                    packageName = appInfo.app.packageName,
                    trailingContent = if (multiSelect) {
                        {
                            Checkbox(
                                state = if (checked) ToggleableState.On else ToggleableState.Off,
                                onClick = null
                            )
                        }
                    } else if (appInfo.isPatched) {
                        {
                            Text(
                                text = stringResource(R.string.patch_target_already_patched),
                                fontSize = 12.sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight(500),
                                color = COUITheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}
