package org.lsposed.npatch.ui.page.manage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.npatch.R
import org.lsposed.npatch.BuildConfig
import org.lsposed.npatch.config.ConfigManager
import org.lsposed.npatch.config.Configs
import org.lsposed.npatch.database.entity.Module
import org.lsposed.npatch.share.Constants
import org.lsposed.npatch.share.LSPConfig

import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.ui.page.ACTION_APPLIST
import org.lsposed.npatch.ui.page.ACTION_STORAGE
import org.lsposed.npatch.ui.page.Navigator
import org.lsposed.npatch.ui.page.Route
import org.lsposed.npatch.ui.page.SelectAppsResult
import org.lsposed.npatch.ui.viewmodel.manage.AppManageViewModel
import org.lsposed.npatch.ui.viewstate.ProcessingState
import nkbe.util.NPackageManager
import nkbe.util.ShizukuApi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.IOException

private const val TAG = "AppManagePage"

@Composable
fun AppManageBody(
    navigator: Navigator,
    searchQuery: String = "",
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState
) {
    val viewModel = viewModel<AppManageViewModel>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()
    val hapticFeedback = LocalHapticFeedback.current

    val filteredList = remember(viewModel.appList, searchQuery) {
        if (searchQuery.isEmpty()) viewModel.appList
        else viewModel.appList.filter {
            it.first.label.contains(searchQuery, true) ||
                    it.first.app.packageName.contains(searchQuery, true)
        }
    }

    var scopeApp by rememberSaveable { mutableStateOf("") }
    val uninstallSuccessfully = stringResource(R.string.manage_uninstall_successfully)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                Toast.makeText(context, uninstallSuccessfully, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.Refresh)
            }
        }
    }

    val isProcessing = viewModel.updateLoaderState is ProcessingState.Processing || viewModel.optimizeState is ProcessingState.Processing
    if (isProcessing) {
        val showLoading = remember { mutableStateOf(true) }
        SuperDialog(
            title = stringResource(R.string.manage_loading),
            show = showLoading.value,
            onDismissRequest = { /* 阻断取消，等待处理完成 */ }
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                InfiniteProgressIndicator()
            }
        }
    }

    when (viewModel.updateLoaderState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.updateLoaderState as ProcessingState.Done
            val updateSuccessfully = stringResource(R.string.manage_update_loader_successfully)
            val updateFailed = stringResource(R.string.manage_update_loader_failed)
            LaunchedEffect(Unit) {
                it.result.onSuccess {
                    Toast.makeText(context, updateSuccessfully, Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, updateFailed, Toast.LENGTH_SHORT).show()
                }
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearUpdateLoaderResult)
            }
        }
    }

    when (viewModel.optimizeState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.optimizeState as ProcessingState.Done
            val optimizeSucceed = stringResource(R.string.manage_optimize_successfully)
            val optimizeFailed = stringResource(R.string.manage_optimize_failed)
            LaunchedEffect(Unit) {
                Toast.makeText(context, if (it.result) optimizeSucceed else optimizeFailed, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearOptimizeResult)
            }
        }
    }

    PullToRefresh(
        isRefreshing = viewModel.isRefreshing,
        onRefresh = { viewModel.dispatch(AppManageViewModel.ViewAction.Refresh) },
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
                                    text = if (searchQuery.isNotEmpty()) stringResource(R.string.manage_no_search_results) else stringResource(R.string.manage_no_apps),
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
                    key = { it.first.app.packageName }
                ) { (appInfo, patchConfig) ->

                    val isLocal = patchConfig.useManager
                    val currentVersion = patchConfig.lspConfig.VERSION_CODE
                    val managerVersion = LSPConfig.instance.VERSION_CODE

                    val showVersionNumber = if (isLocal) {
                        currentVersion < Constants.MIN_ROLLING_VERSION_CODE
                    } else {
                        currentVersion != managerVersion
                    }

                    val canUpdateLoader = if (isLocal) {
                        currentVersion < Constants.MIN_ROLLING_VERSION_CODE
                    } else {
                        (currentVersion != managerVersion) || (patchConfig.managerPackageName != BuildConfig.APPLICATION_ID)
                    }

                    val showDropdown = remember { mutableStateOf(false) }
                    val scopeUpdatedText = stringResource(R.string.manage_module_scope_updated)

                    Box(modifier = Modifier.fillMaxWidth()) {
                        AppItem(
                            icon = {
                                Image(
                                    bitmap = NPackageManager.getIcon(appInfo),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                )
                            },
                            label = appInfo.label,
                            packageName = appInfo.app.packageName,
                            summaryRow = {
                                val patchColor = if (isLocal) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val modeLabel = if (isLocal) {
                                        "${stringResource(R.string.patch_local)} ${stringResource(R.string.manage_rolling)}"
                                    } else {
                                        stringResource(R.string.patch_integrated)
                                    }

                                    Text(
                                        text = modeLabel,
                                        color = patchColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Serif
                                    )

                                    if (showVersionNumber) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = currentVersion.toString(),
                                            color = patchColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Serif
                                        )
                                    }

                                    if (canUpdateLoader) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        with(LocalDensity.current) {
                                            val size = 16.sp * 1.2
                                            Icon(
                                                imageVector = Icons.Filled.KeyboardCapslock,
                                                contentDescription = null,
                                                modifier = Modifier.size(size.toDp()),
                                                tint = patchColor
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            onLongPress = {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            }
                        )

                        SuperListPopup(
                            show = showDropdown.value,
                            alignment = PopupPositionProvider.Align.End,
                            onDismissRequest = { showDropdown.value = false }
                        ) {
                            val actions = mutableListOf<Pair<String, () -> Unit>>()

                            if (canUpdateLoader || BuildConfig.DEBUG) {
                                actions.add(stringResource(R.string.manage_update_loader) to {
                                    scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.UpdateLoader(appInfo, patchConfig)) }
                                })
                            }
                            if (isLocal) {
                                actions.add(stringResource(R.string.manage_module_scope) to {
                                    viewModel.viewModelScope.launch {
                                        scopeApp = appInfo.app.packageName
                                        val activated = withContext(Dispatchers.IO) {
                                            ConfigManager.getModulesForApp(scopeApp).map { it.pkgName }.toSet()
                                        }

                                        val initialSelected = NPackageManager.appList.mapNotNull {
                                            if (activated.contains(it.app.packageName)) it.app.packageName else null
                                        }
                                        val result = navigator.navigateForResult<SelectAppsResult>(
                                            Route.SelectApps(true, initialSelected)
                                        )
                                        if (result is SelectAppsResult.MultipleApps) {
                                            withContext(Dispatchers.IO) {
                                                ConfigManager.getModulesForApp(scopeApp).forEach {
                                                    ConfigManager.deactivateModule(scopeApp, it)
                                                }
                                                result.selected.forEach {
                                                    Log.d(TAG, "Activate ${it.app.packageName} for $scopeApp")
                                                    ConfigManager.activateModule(scopeApp, Module(it.app.packageName, it.app.sourceDir))
                                                }
                                            }
                                            Toast.makeText(context, scopeUpdatedText, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            }
                            val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
                            actions.add(stringResource(R.string.manage_optimize) to {
                                scope.launch {
                                    if (!ShizukuApi.isPermissionGranted) {
                                        Toast.makeText(context, shizukuUnavailable, Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.dispatch(AppManageViewModel.ViewAction.PerformOptimize(appInfo))
                                    }
                                }
                            })
                            actions.add(stringResource(R.string.uninstall) to {
                                val intent = Intent(Intent.ACTION_DELETE).apply {
                                    data = "package:${appInfo.app.packageName}".toUri()
                                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                                }
                                launcher.launch(intent)
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

@Composable
fun AppManageFab(navigator: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shouldSelectDirectory = remember { mutableStateOf(false) }
    val showNewPatchDialog = remember { mutableStateOf(false) }

    val errorText = stringResource(R.string.patch_select_dir_error)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        try {
            if (it.resultCode == Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
            val uri = it.data?.data ?: throw IOException("No data")
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            Configs.storageDirectory = uri.toString()
            Log.i(TAG, "Storage directory: ${uri.path}")
            showNewPatchDialog.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Error when requesting saving directory", e)
            Toast.makeText(context, errorText, Toast.LENGTH_SHORT).show()
        }
    }

    if (shouldSelectDirectory.value) {
        SuperDialog(
            title = stringResource(R.string.patch_select_dir_title),
            show = shouldSelectDirectory.value,
            onDismissRequest = { shouldSelectDirectory.value = false },
        ) {
            Column {
                Text(
                    text = stringResource(R.string.patch_select_dir_text),
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { shouldSelectDirectory.value = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                            shouldSelectDirectory.value = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }

    if (showNewPatchDialog.value) {
        SuperDialog(
            title = stringResource(R.string.screen_new_patch),
            show = showNewPatchDialog.value,
            onDismissRequest = { showNewPatchDialog.value = false },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.patch_from_storage),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navigator.navigate(Route.NewPatch(id = ACTION_STORAGE))
                        showNewPatchDialog.value = false
                    },
                )
                TextButton(
                    text = stringResource(R.string.patch_from_applist),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        navigator.navigate(Route.NewPatch(id = ACTION_APPLIST))
                        showNewPatchDialog.value = false
                    },
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showNewPatchDialog.value = false },
                )
            }
        }
    }

    FloatingActionButton(
        onClick = {
            val uri = Configs.storageDirectory?.toUri()
            if (uri == null) {
                shouldSelectDirectory.value = true
            } else {
                runCatching {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                    if (DocumentFile.fromTreeUri(context, uri)?.exists() == false) throw IOException("Storage directory was deleted")
                }.onSuccess {
                    showNewPatchDialog.value = true
                }.onFailure {
                    Log.w(TAG, "Failed to take persistable permission for saved uri", it)
                    Configs.storageDirectory = null
                    shouldSelectDirectory.value = true
                }
            }
        }
    ) {
        Icon(Icons.Filled.Add, stringResource(R.string.add))
    }
}
