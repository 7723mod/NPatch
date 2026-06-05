package top.nkbe.npatch.ui.page

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nkbe.util.NeoPackageManager
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.AccessibleMenuItem
import top.nkbe.npatch.ui.component.AppItem
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.viewmodel.RepositoryViewModel
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val RepoScopeHorizontalPadding = 12.dp
private const val RepoScopeBackgroundAlpha = 0.82f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RepositoryScopeFilterScreen(
    selectedPackageName: String?,
    onBack: () -> Unit,
    viewModel: RepositoryViewModel = viewModel()
) {
    val targets by viewModel.availableScopeTargets.collectAsStateWithLifecycle()
    val currentScope by viewModel.scopeFilter.collectAsStateWithLifecycle()
    val scrollBehavior = MiuixScrollBehavior()
    val selectedScope = currentScope ?: selectedPackageName

    fun select(packageName: String?) {
        viewModel.setScopeFilter(packageName)
        onBack()
    }

    NPatchScaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.repo_filter_scope_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Back,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            )
        ) {
            item {
                ScopeFilterCard {
                    AccessibleMenuItem(
                        text = stringResource(R.string.repo_filter_clear),
                        summary = stringResource(R.string.off),
                        selected = currentScope == null,
                        onClick = { select(null) }
                    )
                }
            }

            if (targets.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.list_empty),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                items(
                    items = targets,
                    key = { it.packageName }
                ) { target ->
                    Box(
                        modifier = Modifier.animateItem(
                            placementSpec = spring(stiffness = Spring.StiffnessLow)
                        )
                    ) {
                        AppItem(
                            icon = {
                                Image(
                                    bitmap = NeoPackageManager.getIcon(target.appInfo),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(14.dp))
                                )
                            },
                            label = target.label,
                            packageName = target.packageName,
                            trailingContent = {
                                if (selectedScope == target.packageName) {
                                    Icon(
                                        imageVector = MiuixIcons.Regular.Ok,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MiuixTheme.colorScheme.primary
                                    )
                                }
                            },
                            cardColors = backgroundAwareCardColors(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                backgroundAlpha = RepoScopeBackgroundAlpha
                            ),
                            onClick = { select(target.packageName) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeFilterCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = RepoScopeHorizontalPadding)
            .padding(bottom = 8.dp),
        colors = backgroundAwareCardColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            backgroundAlpha = RepoScopeBackgroundAlpha
        ),
        insideMargin = PaddingValues(0.dp),
        showIndication = false,
        content = content
    )
}
