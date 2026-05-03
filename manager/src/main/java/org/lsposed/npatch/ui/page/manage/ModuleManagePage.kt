package org.lsposed.npatch.ui.page.manage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.ui.viewmodel.manage.ModuleManageViewModel
import nkbe.util.NeoPackageManager
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ModuleManageBody(
    searchQuery: String = "",
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val viewModel = viewModel<ModuleManageViewModel>()
    val pullToRefreshState = rememberPullToRefreshState()
    val hapticFeedback = LocalHapticFeedback.current

    val filteredList = remember(viewModel.appList, searchQuery) {
        if (searchQuery.isEmpty()) viewModel.appList
        else viewModel.appList.filter {
            it.appInfo.label.contains(searchQuery, true) ||
                    it.appInfo.app.packageName.contains(searchQuery, true) ||
                    it.metadata.displayName.contains(searchQuery, true)
        }
    }

    PullToRefresh(
        isRefreshing = viewModel.isRefreshing,
        onRefresh = { viewModel.refresh() },
        pullToRefreshState = pullToRefreshState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .hazeSource(state = hazeState),
            contentPadding = contentPadding,
            overscrollEffect = null
        ) {
            if (filteredList.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (NeoPackageManager.appList.isEmpty()) {
                                InfiniteProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.manage_loading),
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            } else {
                                Text(
                                    text = if (searchQuery.isNotEmpty()) stringResource(R.string.manage_no_search_results) else stringResource(R.string.manage_no_modules),
                                    style = MiuixTheme.textStyles.body1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            } else {
                items(
                    items = filteredList,
                    key = { it.appInfo.app.packageName }
                ) { item ->
                    val showDropdown = remember { mutableStateOf(false) }
                    val settingsIntent = remember { NeoPackageManager.getSettingsIntent(item.appInfo.app.packageName) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        AppItem(
                            icon = {
                                Image(
                                    bitmap = NeoPackageManager.getIcon(item.appInfo),
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                                )
                            },
                            label = item.metadata.displayName.ifEmpty { item.appInfo.label },
                            packageName = item.appInfo.app.packageName,
                            summaryRow = {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        item.metadata.isModern -> MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        item.metadata.isLegacy -> MiuixTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                        else -> MiuixTheme.colorScheme.error.copy(alpha = 0.15f)
                                    }
                                ) {
                                    Text(
                                        text = when {
                                            item.metadata.isModern -> stringResource(R.string.manage_module_api_version, item.metadata.targetApiVersion)
                                            item.metadata.isLegacy -> stringResource(R.string.manage_module_api_version, item.metadata.minApiVersion)
                                            else -> stringResource(R.string.manage_module_api_unsupported, item.metadata.minApiVersion)
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Serif,
                                        color = when {
                                            item.metadata.isModern -> MiuixTheme.colorScheme.primary
                                            item.metadata.isLegacy -> MiuixTheme.colorScheme.secondary
                                            else -> MiuixTheme.colorScheme.error
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                if (item.metadata.version.isNotEmpty()) {
                                    Text(
                                        text = item.metadata.version,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            },
                            topRightContent = {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when {
                                        item.metadata.isModern -> MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                        item.metadata.isLegacy -> MiuixTheme.colorScheme.secondary.copy(alpha = 0.15f)
                                        else -> MiuixTheme.colorScheme.error.copy(alpha = 0.15f)
                                    }
                                ) {
                                    Text(
                                        text = when {
                                            item.metadata.isModern -> stringResource(R.string.manage_module_pipeline_modern)
                                            item.metadata.isLegacy -> stringResource(R.string.manage_module_pipeline_legacy)
                                            else -> stringResource(R.string.manage_module_pipeline_unsupported)
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Serif,
                                        color = when {
                                            item.metadata.isModern -> MiuixTheme.colorScheme.primary
                                            item.metadata.isLegacy -> MiuixTheme.colorScheme.secondary
                                            else -> MiuixTheme.colorScheme.error
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            },
                            description = item.metadata.description,
                            warningText = if (item.metadata.isUnsupported) {
                                stringResource(R.string.manage_module_unsupported_warning, item.metadata.minApiVersion)
                            } else null,
                            onClick = {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            onLongPress = {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                        )

                        OverlayListPopup(
                            show = showDropdown.value,
                            alignment = PopupPositionProvider.Align.End,
                            onDismissRequest = { showDropdown.value = false }
                        ) {
                            val actions = mutableListOf<Pair<String, () -> Unit>>()

                            if (settingsIntent != null) {
                                actions.add(stringResource(R.string.manage_module_settings) to {
                                    context.startActivity(settingsIntent)
                                })
                            }
                            actions.add(stringResource(R.string.manage_app_info) to {
                                val intent = Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", item.appInfo.app.packageName, null)
                                )
                                context.startActivity(intent)
                            })

                            ListPopupColumn {
                                actions.forEachIndexed { index, (text, action) ->
                                    DropdownImpl(
                                        text = text,
                                        optionSize = actions.size,
                                        isSelected = false,
                                        dropdownColors = DropdownDefaults.dropdownColors(),
                                        onSelectedIndexChange = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                            showDropdown.value = false
                                            action()
                                        },
                                        index = index
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
