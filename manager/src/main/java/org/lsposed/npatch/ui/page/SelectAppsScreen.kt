package org.lsposed.npatch.ui.page

import android.content.pm.ApplicationInfo
import android.os.Parcelable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.toLowerCase
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import kotlinx.parcelize.Parcelize
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.ui.component.SearchAppBar
import org.lsposed.npatch.ui.viewmodel.SelectAppsViewModel
import nkbe.util.NPackageManager
import nkbe.util.NPackageManager.AppInfo
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
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

    LaunchedEffect(Unit) {
        viewModel.filterAppList(false, filter)
        initialSelected?.let {
            val tmp = initialSelected.toSet()
            viewModel.multiSelected.addAll(NPackageManager.appList.filter { tmp.contains(it.app.packageName) })
        }
    }

    BackHandler {
        navigator.pop()
    }

    Scaffold(
        topBar = {
            SearchAppBar(
                title = { Text(stringResource(R.string.screen_select_apps)) },
                searchText = searchPackage,
                onSearchTextChange = {
                    searchPackage = it
                    viewModel.filterAppList(false, filter)
                },
                onClearClick = {
                    searchPackage = ""
                    viewModel.filterAppList(false, filter)
                },
                onBackClick = {
                    navigator.pop()
                }
            )
        },
        floatingActionButton = {
            if (multiSelect) MultiSelectFab {
                navigator.setResultAndBack(SelectAppsResult.MultipleApps(viewModel.multiSelected))
            }
        }
    ) { innerPadding ->
        val pullToRefreshState = rememberPullToRefreshState()
        PullToRefresh(
            isRefreshing = viewModel.isRefreshing,
            pullToRefreshState = pullToRefreshState,
            onRefresh = { viewModel.filterAppList(true, filter) },
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (multiSelect) MultiSelect()
            else SingleSelect {
                navigator.setResultAndBack(SelectAppsResult.SingleApp(it))
            }
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
private fun SingleSelect(onSelect: (AppInfo) -> Unit) {
    val viewModel = viewModel<SelectAppsViewModel>()
    LazyColumn(
        modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
        overscrollEffect = null
    ) {
        items(
            items = viewModel.filteredList,
            key = { it.app.packageName }
        ) {
            AppItem(
                modifier = Modifier.animateItem(spring(stiffness = Spring.StiffnessLow)),
                onClick = { onSelect(it) },
                icon = {
                    Image(
                        bitmap = NPackageManager.getIcon(it),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                    )
                },
                label = it.label,
                packageName = it.app.packageName
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultiSelect() {
    val viewModel = viewModel<SelectAppsViewModel>()
    LazyColumn(
        modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical(),
        overscrollEffect = null
    ) {
        items(
            items = viewModel.filteredList,
            key = { it.app.packageName }
        ) {
            val checked = viewModel.multiSelected.contains(it)
            AppItem(
                modifier = Modifier.animateItem(spring(stiffness = Spring.StiffnessLow)),
                onClick = {
                    if (checked) viewModel.multiSelected.remove(it)
                    else viewModel.multiSelected.add(it)
                },
                icon = {
                    Image(
                        bitmap = NPackageManager.getIcon(it),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                    )
                },
                label = it.label,
                packageName = it.app.packageName,
                trailingContent = {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null
                    )
                }
            )
        }
    }
}
