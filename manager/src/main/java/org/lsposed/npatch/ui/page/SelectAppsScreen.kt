package org.lsposed.npatch.ui.page

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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.parcelize.Parcelize
import nkbe.util.NPackageManager
import nkbe.util.NPackageManager.AppInfo
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.ui.component.SearchBar
import org.lsposed.npatch.ui.component.SearchBarFake
import org.lsposed.npatch.ui.component.SearchBox
import org.lsposed.npatch.ui.component.SearchPager
import org.lsposed.npatch.ui.component.SearchStatus
import org.lsposed.npatch.ui.component.NPatchScaffold
import org.lsposed.npatch.ui.util.backgroundAwareHazeStyle
import org.lsposed.npatch.ui.viewmodel.SelectAppsViewModel
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
    val filter: (AppInfo) -> Boolean = {
        val packageLowerCase = searchPackage.toLowerCase(Locale.current)
        val contains = it.label.toLowerCase(Locale.current).contains(packageLowerCase) || it.app.packageName.contains(packageLowerCase)
        if (multiSelect) contains && it.isXposedModule
        else contains && it.app.flags and ApplicationInfo.FLAG_SYSTEM == 0
    }

    val title = if (multiSelect) stringResource(R.string.screen_select_modules) else stringResource(R.string.screen_select_apps)
    val searchStatus = remember(multiSelect, title) { SearchStatus(title) }
    val hazeState = remember { HazeState() }
    val hazeStyle = backgroundAwareHazeStyle()

    val scrollBehavior = MiuixScrollBehavior()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    LaunchedEffect(Unit) {
        viewModel.multiSelected.clear()
        viewModel.filterAppList(false, filter)
        initialSelected?.let {
            val tmp = initialSelected.toSet()
            viewModel.multiSelected.addAll(NPackageManager.appList.filter { tmp.contains(it.app.packageName) })
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
            searchStatus.TopAppBarAnim(hazeState = hazeState, hazeStyle = hazeStyle) {
                TopAppBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(
                            modifier = Modifier.padding(start = 16.dp),
                            onClick = { navigator.pop() }
                        ) {
                            val layoutDirection = LocalLayoutDirection.current
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (multiSelect) MultiSelectFab {
                navigator.setResultAndBack(SelectAppsResult.MultipleApps(viewModel.multiSelected))
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
                SearchBarFake(status.label, topPadding, innerPad)
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
        Icon(Icons.Outlined.Done, stringResource(R.string.add))
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
    PullToRefresh(
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
                val checked = if (multiSelect) viewModel.multiSelected.contains(appInfo) else false

                AppItem(
                    modifier = Modifier.animateItem(spring(stiffness = Spring.StiffnessLow)),
                    onClick = {
                        if (multiSelect) {
                            if (checked) viewModel.multiSelected.remove(appInfo)
                            else viewModel.multiSelected.add(appInfo)
                        } else {
                            onSingleSelect(appInfo)
                        }
                    },
                    icon = {
                        Image(
                            bitmap = NPackageManager.getIcon(appInfo),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                        )
                    },
                    label = appInfo.label,
                    packageName = appInfo.app.packageName,
                    trailingContent = if (multiSelect) {
                        {
                            @Suppress("DEPRECATION")
                            Checkbox(checked = checked, onCheckedChange = null)
                        }
                    } else null
                )
            }
        }
    }
}
