package org.lsposed.npatch.ui.page.manage

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState

import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.viewmodel.manage.ModuleManageViewModel
import nkbe.util.NPackageManager
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun ModuleManageBody() {
    val context = LocalContext.current
    val viewModel = viewModel<ModuleManageViewModel>()
    val pullToRefreshState = rememberPullToRefreshState()

    PullToRefresh(
        isRefreshing = viewModel.isRefreshing,
        onRefresh = { viewModel.refresh() },
        pullToRefreshState = pullToRefreshState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (viewModel.appList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (NPackageManager.appList.isEmpty()) {
                        InfiniteProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.manage_loading),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.manage_no_modules),
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollEndHaptic()
                    .overScrollVertical(),
                overscrollEffect = null
            ) {
                items(
                    items = viewModel.appList,
                    key = { it.first.app.packageName }
                ) {
                    val showDropdown = remember { mutableStateOf(false) }
                    val hapticFeedback = LocalHapticFeedback.current
                    val settingsIntent = remember { NPackageManager.getSettingsIntent(it.first.app.packageName) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        AppItem(
                            modifier = Modifier.clickable {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            icon = NPackageManager.getIcon(it.first),
                            label = it.first.label,
                            packageName = it.first.app.packageName,
                            additionalContent = {
                                Text(
                                    text = it.second.description,
                                    style = MiuixTheme.textStyles.footnote1
                                )
                                Text(
                                    text = buildAnnotatedString {
                                        append(AnnotatedString("API", SpanStyle(color = MiuixTheme.colorScheme.secondary)))
                                        append("  ")
                                        append(it.second.api.toString())
                                    },
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Serif,
                                    style = MiuixTheme.textStyles.footnote1
                                )
                            }
                        )

                        // Miuix 原生下拉菜单
                        SuperListPopup(
                            show = showDropdown,
                            alignment = PopupPositionProvider.Align.End, // 自动右对齐
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
                                    Uri.fromParts("package", it.first.app.packageName, null)
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
