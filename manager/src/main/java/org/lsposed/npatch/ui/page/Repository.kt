package org.lsposed.manager.ui.compose.repository

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.SearchBarFake
import org.lsposed.npatch.ui.component.SearchBox
import org.lsposed.npatch.ui.component.SearchPager
import org.lsposed.npatch.ui.component.SearchStatus
import org.lsposed.npatch.ui.component.NPatchScaffold
import org.lsposed.npatch.ui.page.Navigator
import org.lsposed.npatch.ui.page.Route
import org.lsposed.npatch.ui.util.backgroundAwareCardColors
import org.lsposed.npatch.ui.util.backgroundAwareHazeStyle
import org.lsposed.npatch.ui.viewmodel.RepoSort
import org.lsposed.npatch.ui.viewmodel.RepoUiModel
import org.lsposed.npatch.ui.viewmodel.RepositoryViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Favorites
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RepositoryScreen(
    navigator: Navigator,
    viewModel: RepositoryViewModel = viewModel()
) {
    val searchGoText = stringResource(android.R.string.search_go)
    val searchStatus = remember { SearchStatus(searchGoText) }

    val uiModels by viewModel.uiModels.collectAsStateWithLifecycle()
    val currentSort by viewModel.sortOrder.collectAsStateWithLifecycle()
    val isUpgradableFirst by viewModel.upgradableFirst.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()

    val pullToRefreshState = rememberPullToRefreshState()
    val scrollBehavior = MiuixScrollBehavior()
    val dynamicTopPadding by remember {
        derivedStateOf { 12.dp * (1f - scrollBehavior.state.collapsedFraction) }
    }

    val hazeState = remember { HazeState() }
    val hazeStyle = backgroundAwareHazeStyle()

    val showSortMenu = remember { mutableStateOf(false) }
    val sortOptions = listOf(
        stringResource(R.string.sort_by_update_time),
        stringResource(R.string.sort_by_install_time),
        stringResource(R.string.sort_by_name),
        "Stars"
    )

    LaunchedEffect(searchStatus.searchText) {
        viewModel.onSearchQueryChanged(searchStatus.searchText)
    }

    val refreshTexts = listOf(
        stringResource(R.string.refresh_pulling),
        stringResource(R.string.refresh_release),
        stringResource(R.string.refresh_refresh),
        stringResource(R.string.refresh_complete),
    )

    NPatchScaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            searchStatus.TopAppBarAnim(hazeState = hazeState, hazeStyle = hazeStyle) {
                TopAppBar(
                    color = Color.Transparent,
                    title = stringResource(R.string.module_repo),
                    scrollBehavior = scrollBehavior,
                    actions = {
                        Box {
                            IconButton(onClick = { showSortMenu.value = true }) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Sort,
                                    contentDescription = "Sort"
                                )
                            }
                            SuperListPopup(
                                show = showSortMenu.value,
                                alignment = PopupPositionProvider.Align.TopEnd,
                                onDismissRequest = { showSortMenu.value = false }
                            ) {
                                ListPopupColumn {
                                    DropdownImpl(
                                        text = stringResource(R.string.sort_upgradable_first),
                                        optionSize = sortOptions.size + 1,
                                        isSelected = isUpgradableFirst,
                                        index = 0,
                                        onSelectedIndexChange = {
                                            viewModel.toggleUpgradableFirst()
                                            showSortMenu.value = false
                                        }
                                    )
                                    sortOptions.forEachIndexed { index, text ->
                                        val targetSort = when (index) {
                                            0 -> RepoSort.UPDATED
                                            1 -> RepoSort.CREATED
                                            2 -> RepoSort.NAME
                                            else -> RepoSort.STARS
                                        }
                                        DropdownImpl(
                                            text = text,
                                            optionSize = sortOptions.size + 1,
                                            isSelected = currentSort == targetSort,
                                            index = index + 1,
                                            onSelectedIndexChange = {
                                                viewModel.setSortOrder(targetSort)
                                                showSortMenu.value = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        },
        popupHost = {
            searchStatus.SearchPager(
                searchBarTopPadding = dynamicTopPadding,
                defaultResult = {
                    val searchPadding = PaddingValues(bottom = 24.dp)
                    PullToRefresh(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        pullToRefreshState = pullToRefreshState,
                        refreshTexts = refreshTexts,
                        contentPadding = searchPadding
                    ) {
                        RepoListContent(
                            uiModels = uiModels,
                            onRepoClick = { navigator.navigate(Route.RepoDetail(it)) },
                            contentPadding = searchPadding,
                            hazeState = hazeState,
                            scrollBehavior = scrollBehavior
                        )
                    }
                }
            ) {}
            MiuixPopupHost()
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
                }
            }
        ) { boxHeight ->
            val padding = PaddingValues(
                top = innerPadding.calculateTopPadding() + boxHeight.value,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            )
            PullToRefresh(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.refresh() },
                pullToRefreshState = pullToRefreshState,
                topAppBarScrollBehavior = scrollBehavior,
                refreshTexts = refreshTexts,
                contentPadding = padding,
                content = {
                    RepoListContent(
                        uiModels = uiModels,
                        onRepoClick = { navigator.navigate(Route.RepoDetail(it)) },
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding() + boxHeight.value,
                            bottom = innerPadding.calculateBottomPadding() + 24.dp
                        ),
                        hazeState = hazeState,
                        scrollBehavior = scrollBehavior
                    )
                }
            )
        }
    }
}

@Composable
fun RepoListContent(
    uiModels: List<RepoUiModel>,
    onRepoClick: (String) -> Unit,
    isInitialLoading: Boolean = false,
    contentPadding: PaddingValues,
    hazeState: HazeState,
    scrollBehavior: ScrollBehavior
) {
    if (isInitialLoading && uiModels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            InfiniteProgressIndicator()
        }
    } else if (uiModels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.list_empty),
                color = colorScheme.onSurfaceVariantSummary
            )
        }
    } else {
        LazyColumn(
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .hazeSource(state = hazeState)
        ) {
            items(
                items = uiModels,
                key = { it.module.name ?: it.hashCode() }
            ) { item ->
                RepositoryItem(item = item, onClick = {
                    item.module.name?.let(onRepoClick)
                })
            }
        }
    }
}

@Composable
fun RepositoryItem(item: RepoUiModel, onClick: () -> Unit) {
    val context = LocalContext.current
    val module = item.module
    val appName = module.description ?: module.name ?: "Unknown"
    val packageName = module.name ?: ""

    val releaseTimeStr = item.module.latestReleaseTime

    val updatedTime = remember(releaseTimeStr) {
        getRelativeTime(context, releaseTimeStr)
    }

    val showMenu = remember { mutableStateOf(false) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            colors = backgroundAwareCardColors(),
            insideMargin = PaddingValues(16.dp),
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Sink,
            onClick = onClick,
            onLongPress = { showMenu.value = true }
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = appName,
                        fontSize = 17.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (item.isUpgradable) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = MiuixIcons.Regular.Download,
                            contentDescription = stringResource(R.string.need_update),
                            tint = colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else if (item.isInstalled) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = MiuixIcons.Regular.Ok,
                            contentDescription = stringResource(R.string.installed),
                            tint = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Column {
                    Text(
                        text = "${stringResource(R.string.package_name)}：$packageName",
                        fontSize = 12.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurfaceVariantSummary,
                    )

                    val author = module.collaborators?.firstOrNull()?.name
                        ?: module.collaborators?.firstOrNull()?.login
                    if (author != null) {
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = "${stringResource(R.string.author)}：$author",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 1.dp),
                            fontWeight = FontWeight(550),
                            color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 1
                        )
                    }
                }
            }

            if (!module.summary.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = module.summary!!,
                    fontSize = 14.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 4,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                thickness = 0.5.dp,
                color = colorScheme.outline.copy(alpha = 0.5f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Favorites,
                        contentDescription = "Stars",
                        tint = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${item.stargazerCount}",
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Recent,
                        contentDescription = "Updated",
                        tint = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = updatedTime,
                        style = MiuixTheme.textStyles.body2,
                        color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }

            SuperListPopup(
                show = showMenu.value,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = { showMenu.value = false }
            ) {
                ListPopupColumn {
                    DropdownImpl(
                        text = androidx.compose.ui.res.stringResource(R.string.menu_open_in_browser),
                        optionSize = 2,
                        isSelected = false,
                        index = 0,
                        onSelectedIndexChange = {
                            val url = "https://modules.lsposed.org/module/${item.module.name}"
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                url.toUri()
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            showMenu.value = false
                        }
                    )
                    DropdownImpl(
                        text = androidx.compose.ui.res.stringResource(R.string.download_latest_version),
                        optionSize = 2,
                        isSelected = false,
                        index = 1,
                        onSelectedIndexChange = {
                            val url = if (!module.sourceUrl.isNullOrEmpty()) {
                                if (module.sourceUrl!!.endsWith("/")) {
                                    "${module.sourceUrl}releases/latest"
                                } else {
                                    "${module.sourceUrl}/releases/latest"
                                }
                            } else {
                                "https://modules.lsposed.org/module/${item.module.name}"
                            }
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                url.toUri()
                            )
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            showMenu.value = false
                        }
                    )
                }
            }
        }
    }
}

fun getRelativeTime(context: Context, timeString: String?): String {
    if (timeString == null) return "N/A"
    try {
        val instant = java.time.Instant.parse(timeString)
        val time = instant.toEpochMilli()
        val now = System.currentTimeMillis()
        val diff = now - time

        val oneYearMillis = 365L * 24 * 60 * 60 * 1000

        return when {
            diff < 60 * 1000 -> context.getString(R.string.time_just_now)
            diff < 60 * 60 * 1000 -> context.getString(R.string.time_minutes_ago, diff / (60 * 1000))
            diff < 24 * 60 * 60 * 1000 -> context.getString(R.string.time_hours_ago, diff / (60 * 60 * 1000))
            diff < 30L * 24 * 60 * 60 * 1000 -> context.getString(R.string.time_days_ago, diff / (24 * 60 * 60 * 1000))
            diff >= oneYearMillis -> {
                val date = Date(time)
                val sdf = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                sdf.format(date)
            }

            else -> {
                val date = Date(time)
                val sdf = DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault())
                sdf.format(date)
            }
        }
    } catch (_: Exception) {
        return "N/A"
    }
}
