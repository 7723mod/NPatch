package top.nkbe.npatch.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.lsposed.manager.ui.compose.repository.RepositoryDetailViewModel
import top.nkbe.npatch.repo.OnlineModule
import top.nkbe.npatch.repo.ReleaseAsset
import top.nkbe.npatch.ui.component.GithubMarkdown
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.collections.take
import top.nkbe.npatch.R
import top.nkbe.npatch.repo.Release
import kotlin.math.min

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RepositoryDetailScreen(
    packageName: String,
    onBack: () -> Unit,
    viewModel: RepositoryDetailViewModel = viewModel()
) {
    LaunchedEffect(packageName) {
        viewModel.loadModule(packageName)
    }

    val module by viewModel.module.collectAsStateWithLifecycle()
    val releases by viewModel.releases.collectAsStateWithLifecycle()
    val installedModule by viewModel.installedState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val msgReadme = stringResource(R.string.module_readme)
    val msgReleases = stringResource(R.string.module_releases)
    val msgInfo = stringResource(R.string.module_information)

    val tabs = remember(module, msgReadme, msgReleases, msgInfo) {
        val list = mutableListOf(msgReadme, msgReleases)
        val hasLinks = !module?.homepageUrl.isNullOrEmpty() || !module?.sourceUrl.isNullOrEmpty()
        if (hasLinks || !module?.collaborators.isNullOrEmpty()) {
            list.add(msgInfo)
        }
        list
    }

    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scrollBehavior: ScrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberHazeState()
    val hazeStyle = HazeBlurStyle(
        backgroundColor = colorScheme.surface,
        colorEffect = HazeColorEffect.tint(colorScheme.surface.copy(0.8f))
    )

    val msgUnknownAuthor = stringResource(R.string.unknown_author)
    val displayAuthorName = remember(module, msgUnknownAuthor) {
        module?.collaborators?.firstOrNull()?.name
            ?: module?.collaborators?.firstOrNull()?.login
            ?: msgUnknownAuthor
    }


    val tabRowHeight by remember { mutableStateOf(40.dp) }
    val uriHandler = LocalUriHandler.current
    NPatchScaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.hazeEffect(hazeState) {
                    blurEffect {
                        style = hazeStyle
                        blurRadius = 30.dp
                        noiseFactor = 0f
                    }
                },
                color = Color.Transparent,
                title = stringResource(R.string.module_app_info),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            MiuixIcons.Regular.Back,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                actions = {
                    if (!module?.name.isNullOrEmpty()) {
                        IconButton(onClick = {
                            module?.name?.let {
                                uriHandler.openUri("https://modules.lsposed.org/module/$it")
                            }
                        }) {
                            Icon(
                                MiuixIcons.Regular.Link,
                                contentDescription = stringResource(R.string.menu_open_in_browser)
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        var collapsedFraction by remember { mutableFloatStateOf(scrollBehavior.state.collapsedFraction) }
        LaunchedEffect(scrollBehavior.state.collapsedFraction) {
            snapshotFlow { scrollBehavior.state.collapsedFraction }.collectLatest {
                collapsedFraction = it
            }
        }
        val dynamicTopPadding by remember { derivedStateOf { 12.dp * (1f - collapsedFraction) } }

        var headerHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            if (module == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    InfiniteProgressIndicator()
                }
                return@NPatchScaffold
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Top,
                beyondViewportPageCount = pagerState.pageCount,
            ) { page ->
                val currentTab = tabs.getOrNull(page)
                val pageInnerPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + dynamicTopPadding + headerHeight + 6.dp,
                    bottom = innerPadding.calculateBottomPadding() + WindowInsets.systemBars.asPaddingValues()
                        .calculateBottomPadding(),
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection)
                )

                when (currentTab) {
                    msgReadme -> ReadmeTab(
                        onlineModule = module,
                        scrollBehavior = scrollBehavior,
                        contentPadding = pageInnerPadding,
                        hazeState = hazeState
                    )

                    msgReleases -> ReleasesTab(
                        releases = releases,
                        context = context,
                        scrollBehavior = scrollBehavior,
                        contentPadding = pageInnerPadding,
                        hazeState = hazeState
                    )

                    msgInfo -> InfoTab(
                        module = module,
                        context = context,
                        headerAuthorName = displayAuthorName,
                        scrollBehavior = scrollBehavior,
                        contentPadding = pageInnerPadding,
                        hazeState = hazeState
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .padding(
                        top = innerPadding.calculateTopPadding() + dynamicTopPadding,
                        start = innerPadding.calculateStartPadding(layoutDirection),
                        end = innerPadding.calculateEndPadding(layoutDirection)
                    )
                    .hazeEffect(hazeState) {
                        blurEffect {
                            style = hazeStyle
                            blurRadius = 30.dp
                            noiseFactor = 0f
                        }
                    }
                    .padding(bottom = 6.dp)
                    .onGloballyPositioned {
                        with(density) { headerHeight = it.size.height.toDp() }
                    }
            ) {
                // 模块信息卡片
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 4.dp),
                    colors = backgroundAwareCardColors()
                ) {
                    Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = module?.description ?: module?.name ?: "",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight(550),
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${stringResource(R.string.sort_by_package_name)}：${module?.name}",
                                    fontSize = 12.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${stringResource(R.string.module_information_collaborators)}：$displayAuthorName",
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(bottom = 1.dp),
                                    color = colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1
                                )
                            }
                        }

                        if (installedModule != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.15f))
                            Spacer(modifier = Modifier.height(12.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val hasUpdate = remember(installedModule, releases) {
                                    val installedVersion = installedModule?.versionName
                                    releases.firstOrNull()?.let { r ->
                                        val remote = r.name ?: r.tagName
                                        remote != null && remote != installedVersion
                                    } ?: false
                                }
                                Surface(
                                    color = colorScheme.onBackgroundVariant.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            MiuixIcons.Regular.Ok,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "${stringResource(R.string.installed)} ${installedModule?.versionName}",
                                            fontSize = 11.sp,
                                            color = colorScheme.onSurface
                                        )
                                    }
                                }
                                if (hasUpdate) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = colorScheme.primary.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.need_update),
                                                fontSize = 11.sp,
                                                color = colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                TabRow(
                    tabs = tabs,
                    selectedTabIndex = pagerState.currentPage,
                    onTabSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
                    colors = TabRowDefaults.tabRowColors(backgroundColor = Color.Transparent),
                    height = tabRowHeight,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Composable
fun ReadmeTab(
    onlineModule: OnlineModule?,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    hazeState: HazeState
) {
    val content = onlineModule?.readmeHTML
    if (content.isNullOrEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.list_empty),
                color = colorScheme.onSurfaceVariantSummary
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .hazeSource(state = hazeState),
            contentPadding = contentPadding
        ) {
            item {
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    colors = backgroundAwareCardColors(),
                ) {
                    GithubMarkdown(content = content)
                }
            }
        }
    }
}


@Composable
fun ReleasesTab(
    releases: List<Release>,
    context: Context,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    hazeState: HazeState
) {
    val installPageSize = 5
    val subsequentPageSize = 10
    if (releases.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.module_release_no_more),
                color = colorScheme.onSurfaceVariantSummary
            )
        }
        return
    }

    var visibleItemCount by remember {
        mutableIntStateOf(min(subsequentPageSize, releases.size))
    }

    val scope = rememberCoroutineScope()

    val hasMoreItems = visibleItemCount < releases.size

    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .scrollEndHaptic()
            .overScrollVertical()
            .hazeSource(state = hazeState)
    ) {
        items(releases.take(visibleItemCount)) { release ->
            Box(Modifier.padding(horizontal = 12.dp)) {
                ReleaseCard(release, context)
            }
        }

        if (hasMoreItems) {
            item {
                TextButton(
                    text = stringResource(R.string.module_release_load_more),
                    onClick = {
                        scope.launch {
                            visibleItemCount = min(
                                releases.size,
                                visibleItemCount + installPageSize
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Composable
fun ReleaseCard(release: Release, context: Context) {
    val dateStr = remember(release.publishedAt) {
        try {
            val instant = Instant.parse(release.publishedAt)
            val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (e: Exception) {
            release.publishedAt ?: ""
        }
    }

    val unknownVersion = stringResource(R.string.unknown_version)
    val title = remember(release.name, release.tagName, unknownVersion) {
        if (!release.name.isNullOrBlank()) release.name else release.tagName
            ?: unknownVersion
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = backgroundAwareCardColors(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                        .weight(1f)
                ) {
                    Text(
                        text = title!!,
                        fontSize = 17.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = release.tagName ?: "",
                        fontSize = 12.sp,
                        fontWeight = FontWeight(550),
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = dateStr,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                        .align(Alignment.Top)
                )
            }

            val hasAssets = !release.releaseAssets.isNullOrEmpty()
            val hasDescription = !release.descriptionHTML.isNullOrEmpty()

            AnimatedVisibility(
                visible = hasAssets || hasDescription,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    if (hasDescription) {
                        Column {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
                                thickness = 0.5.dp,
                                color = colorScheme.outline.copy(alpha = 0.15f)
                            )
                            GithubMarkdown(content = release.descriptionHTML!!)
                        }
                    }

                    if (hasAssets) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                            thickness = 0.5.dp,
                            color = colorScheme.outline.copy(alpha = 0.15f)
                        )

                        release.releaseAssets?.forEachIndexed { index, asset ->
                            val bottomPadding =
                                if (index == release.releaseAssets!!.lastIndex) 16.dp else 8.dp

                            ReleaseAssetItem(
                                asset = asset,
                                bottomPadding = bottomPadding,
                                context = context
                            )

                            if (index != release.releaseAssets!!.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(
                                        start = 12.dp,
                                        end = 12.dp,
                                        bottom = 8.dp
                                    ),
                                    thickness = 0.5.dp,
                                    color = colorScheme.outline.copy(alpha = 0.1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoTab(
    module: OnlineModule?,
    context: Context,
    headerAuthorName: String,
    scrollBehavior: ScrollBehavior,
    contentPadding: PaddingValues,
    hazeState: HazeState
) {
    if (module == null) return
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .scrollEndHaptic()
            .overScrollVertical()
            .hazeSource(state = hazeState),
        contentPadding = contentPadding
    ) {

        if (!module.homepageUrl.isNullOrEmpty() || !module.sourceUrl.isNullOrEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    colors = backgroundAwareCardColors(),
                ) {
                    if (!module.homepageUrl.isNullOrEmpty()) {
                        InfoRowItem(
                            icon = MiuixIcons.Regular.Link,
                            title = stringResource(R.string.module_information_homepage),
                            summary = module.homepageUrl,
                            onClick = {
                                module.homepageUrl?.let { url ->
                                    uriHandler.openUri(url)
                                }
                            }
                        )
                    }
                    if (!module.sourceUrl.isNullOrEmpty()) {
                        InfoRowItem(
                            icon = MiuixIcons.Regular.File,
                            title = stringResource(R.string.module_information_source_url),
                            summary = module.sourceUrl,
                            onClick = {
                                module.sourceUrl?.let { url ->
                                    uriHandler.openUri(url)
                                }
                            }
                        )
                    }
                }
            }
        }

        val collaborators = module.collaborators
        if (!collaborators.isNullOrEmpty()) {
            val shouldShowContributors = if (collaborators.size == 1) {
                val singleName = collaborators[0].name ?: collaborators[0].login ?: ""
                singleName != headerAuthorName
            } else {
                true
            }

            if (shouldShowContributors) {
                item {
                    SmallTitle(text = stringResource(R.string.module_information_collaborators))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        colors = backgroundAwareCardColors(),
                    ) {
                        Column {
                            collaborators.forEachIndexed { index, collaborator ->
                                val name = collaborator.name ?: collaborator.login
                                ?: stringResource(R.string.unknown_author)
                                InfoRowItem(
                                    icon = MiuixIcons.Regular.Contacts,
                                    title = name,
                                    summary = collaborator.login,
                                    onClick = {
                                        val url = "https://github.com/${collaborator.login}"
                                        module.homepageUrl?.let { url ->
                                            uriHandler.openUri(url)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoRowItem(icon: ImageVector, title: String, summary: String?, onClick: () -> Unit) {
    if (summary.isNullOrEmpty()) return

    ArrowPreference(
        title = title,
        summary = summary,
        insideMargin = PaddingValues(vertical = 16.dp, horizontal = 12.dp),
        startAction = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        },
        onClick = onClick
    )
}

@SuppressLint("Range")
@Composable
fun ReleaseAssetItem(
    asset: ReleaseAsset,
    bottomPadding: Dp,
    context: Context
) {
    val count = asset.downloadCount
    val countStr =
        pluralStringResource(R.plurals.module_release_assets_download_count, count, count)
    val metaText = remember(asset.size, countStr) {
        val sizeInMb = asset.size.toDouble() / (1024 * 1024)
        val sizeStr = String.format(Locale.getDefault(), "%.1f MB", sizeInMb)
        "$sizeStr / $countStr"
    }

    val showDownloadDialog = remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadId by remember { mutableLongStateOf(-1L) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return

                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(id)
                isDownloading = false
                progress = 1f
                downloadId = -1L

                if (uri != null) {
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(installIntent)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    if (isDownloading && downloadId != -1L) {
        LaunchedEffect(downloadId) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            while (isDownloading) {
                val q = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(q)
                if (cursor != null && cursor.moveToFirst()) {
                    val bytesDownloaded = cursor.getInt(
                        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val bytesTotal = cursor.getInt(
                        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    if (bytesTotal > 0) {
                        progress = bytesDownloaded.toFloat() / bytesTotal.toFloat()
                    }
                    cursor.close()
                }
                delay(300)
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = bottomPadding)
                .weight(1f)
        ) {
            Text(
                text = asset.name ?: stringResource(R.string.unknown_asset),
                fontSize = 14.sp,
                color = colorScheme.onSurface
            )
            Text(
                text = metaText,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        IconButton(
            modifier = Modifier.padding(bottom = bottomPadding),
            backgroundColor = colorScheme.secondaryContainer.copy(alpha = 0.5f),
            minHeight = 35.dp,
            minWidth = 35.dp,
            onClick = {
                asset.downloadUrl?.let { url ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                }
            },
        ) {
            Icon(
                modifier = Modifier.size(18.dp),
                imageVector = MiuixIcons.Regular.Link,
                tint = colorScheme.onSurface,
                contentDescription = null
            )
        }

        IconButton(
            modifier = Modifier.padding(end = 12.dp, bottom = bottomPadding),
            backgroundColor = colorScheme.secondaryContainer.copy(alpha = 0.8f),
            minHeight = 35.dp,
            minWidth = 35.dp,
            enabled = !isDownloading,
            onClick = { showDownloadDialog.value = true },
        ) {
            if (isDownloading) {
                CircularProgressIndicator(
                    progress = progress,
                    size = 20.dp,
                    strokeWidth = 2.dp
                )
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        modifier = Modifier.size(18.dp),
                        imageVector = MiuixIcons.Regular.Download,
                        tint = colorScheme.onSurface,
                        contentDescription = stringResource(R.string.download_asset)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.module_release_view_assets),
                        color = colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
    val uriHandler = LocalUriHandler.current
    OverlayDialog(
        title = stringResource(R.string.download_asset),
        summary = asset.name ?: "",
        show = showDownloadDialog.value,
        onDismissRequest = { showDownloadDialog.value = false }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                text = stringResource(R.string.menu_open_in_browser),
                onClick = {
                    asset.downloadUrl?.let { url ->
                        uriHandler.openUri(url)
                    }
                    showDownloadDialog.value = false
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showDownloadDialog.value = false },
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    text = stringResource(R.string.download_asset),
                    onClick = {
                        asset.downloadUrl?.let { url ->
                            val request = DownloadManager.Request(url.toUri())
                                .setTitle(asset.name ?: "Download")
                                .setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                                )
                                .setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    asset.name ?: "download"
                                )
                            val dm =
                                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                            downloadId = dm.enqueue(request)
                            isDownloading = true
                            progress = 0f
                            Toast.makeText(context, R.string.download_asset, Toast.LENGTH_SHORT)
                                .show()
                        }
                        showDownloadDialog.value = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    }
}
