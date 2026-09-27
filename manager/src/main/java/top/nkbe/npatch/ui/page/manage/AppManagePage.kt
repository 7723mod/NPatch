package top.nkbe.npatch.ui.page.manage

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
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.nkbe.npatch.R
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.database.entity.LoadedModule
import top.nkbe.npatch.manager.ModuleScopeSyncStore
import top.nkbe.npatch.manager.DiagnosticLogExporter
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.LSPConfig

import top.nkbe.npatch.ui.component.AccessibleMenuItem
import top.nkbe.npatch.ui.component.AppItem
import top.nkbe.npatch.ui.component.NPatchPullToRefresh
import top.nkbe.npatch.ui.page.ACTION_APPLIST
import top.nkbe.npatch.ui.page.ACTION_STORAGE
import top.nkbe.npatch.ui.page.Navigator
import top.nkbe.npatch.ui.page.Route
import top.nkbe.npatch.ui.page.SelectAppsResult
import top.nkbe.npatch.ui.viewmodel.manage.AppManageViewModel
import top.nkbe.npatch.ui.viewmodel.manage.ModuleManageViewModel
import top.nkbe.npatch.ui.viewstate.ProcessingState
import nkbe.util.NeoPackageManager
import nkbe.util.ShizukuApi
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InfiniteProgressIndicator
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.PopupPositionProvider
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.rememberPullToRefreshState
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.overlay.OverlayListPopup
import io.github.suqi8.coui.kmp.overlay.OverlayLoadingDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
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
    val moduleManageViewModel = viewModel<ModuleManageViewModel>()
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

    val uninstallSuccessfully = stringResource(R.string.manage_uninstall_successfully)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                Toast.makeText(context, uninstallSuccessfully, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.Refresh)
            }
        }
    }

    val isProcessing = viewModel.updateLoaderState is ProcessingState.Processing 
            || viewModel.optimizeState is ProcessingState.Processing
            || viewModel.forceStopState is ProcessingState.Processing
            || viewModel.forceRestartState is ProcessingState.Processing
    if (isProcessing) {
        val showLoading = remember { mutableStateOf(true) }
        OverlayLoadingDialog(
            text = stringResource(R.string.manage_loading),
            show = showLoading.value,
            onDismissRequest = { /* 阻断取消，等待处理完成 */ }
        )
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

    when (viewModel.forceStopState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.forceStopState as ProcessingState.Done
            val forceStopSucceed = stringResource(R.string.manage_force_stop_successfully)
            val forceStopFailed = stringResource(R.string.manage_force_stop_failed)
            LaunchedEffect(Unit) {
                Toast.makeText(context, if (it.result) forceStopSucceed else forceStopFailed, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearForceStopResult)
            }
        }
    }

    when (viewModel.forceRestartState) {
        is ProcessingState.Idle -> Unit
        is ProcessingState.Processing -> Unit
        is ProcessingState.Done -> {
            val it = viewModel.forceRestartState as ProcessingState.Done
            val forceRestartSucceed = stringResource(R.string.manage_force_restart_successfully)
            val forceRestartFailed = stringResource(R.string.manage_force_restart_failed)
            LaunchedEffect(Unit) {
                Toast.makeText(context, if (it.result) forceRestartSucceed else forceRestartFailed, Toast.LENGTH_SHORT).show()
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearForceRestartResult)
            }
        }
    }

    NPatchPullToRefresh(
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
                            if (NeoPackageManager.appList.isEmpty()) {
                                InfiniteProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.manage_loading),
                                    style = COUITheme.textStyles.body1,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                                )
                            } else {
                                Text(
                                    text = if (searchQuery.isNotEmpty()) stringResource(R.string.manage_no_search_results) else stringResource(R.string.manage_no_apps),
                                    style = COUITheme.textStyles.body1,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary
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
                                    bitmap = NeoPackageManager.getIcon(appInfo),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
                                )
                            },
                            label = appInfo.label,
                            packageName = appInfo.app.packageName,
                            summaryRow = {
                                val patchColor = if (isLocal) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurfaceVariantSummary

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

                        OverlayListPopup(
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
                                        val targetAppPkg = appInfo.app.packageName
                                        val activated = withContext(Dispatchers.IO) {
                                            ConfigManager.getModulesForApp(targetAppPkg).map { it.pkgName }.toSet()
                                        }

                                        val initialSelected = NeoPackageManager.appList.mapNotNull {
                                            if (activated.contains(it.app.packageName)) it.app.packageName else null
                                        }
                                        val result = navigator.navigateForResult<SelectAppsResult>(
                                            Route.SelectApps(true, initialSelected)
                                        )
                                        if (result is SelectAppsResult.MultipleApps) {
                                            withContext(Dispatchers.IO) {
                                                val previousModules = ConfigManager.getModulesForApp(targetAppPkg)
                                                val affectedPackages = buildSet {
                                                    previousModules.forEach { add(it.pkgName) }
                                                    result.selected.forEach { add(it.app.packageName) }
                                                }
                                                previousModules.forEach {
                                                    ConfigManager.deactivateModule(targetAppPkg, it)
                                                }
                                                result.selected.forEach {
                                                    Log.d(TAG, "Activate ${it.app.packageName} for $targetAppPkg")
                                                    ConfigManager.activateModule(targetAppPkg, LoadedModule(it.app.packageName, it.app.sourceDir))
                                                }
                                                if (ShizukuApi.isReady) {
                                                    // Notify both removed and newly added modules so they do not
                                                    // keep stale scope state after a scope edit.
                                                    ModuleScopeSyncStore.syncModuleScopes(affectedPackages)
                                                }
                                            }
                                            moduleManageViewModel.refreshScopedActivationState()
                                            Toast.makeText(context, scopeUpdatedText, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                })
                            }
                            actions.add(stringResource(R.string.manage_export_diagnostics) to {
                                scope.launch {
                                    runCatching {
                                        val result = DiagnosticLogExporter.export(
                                            context,
                                            appInfo.app.packageName,
                                        )
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            result.file,
                                        )
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "application/zip"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            clipData = ClipData.newUri(
                                                context.contentResolver,
                                                result.file.name,
                                                uri,
                                            )
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }.let { shareIntent ->
                                            context.startActivity(
                                                Intent.createChooser(
                                                    shareIntent,
                                                    context.getString(R.string.manage_export_diagnostics_chooser),
                                                ),
                                            )
                                        }
                                    }.onFailure {
                                        Log.e(TAG, "Failed to export diagnostics for ${appInfo.app.packageName}", it)
                                        Toast.makeText(
                                            context,
                                            R.string.manage_export_diagnostics_failed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            })
                            val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
                            actions.add(stringResource(R.string.manage_optimize) to {
                                scope.launch {
                                    if (!ShizukuApi.isReady) {
                                        Toast.makeText(context, shizukuUnavailable, Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.dispatch(AppManageViewModel.ViewAction.PerformOptimize(appInfo))
                                    }
                                }
                            })
                            actions.add(stringResource(R.string.manage_force_stop) to {
                                if (ShizukuApi.isReady) {
                                    scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.PerformForceStop(appInfo)) }
                                } else {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = "package:${appInfo.app.packageName}".toUri()
                                    }
                                    context.startActivity(intent)
                                }
                            })
                            actions.add(stringResource(R.string.manage_force_restart) to {
                                if (ShizukuApi.isReady) {
                                    scope.launch {
                                        viewModel.dispatch(AppManageViewModel.ViewAction.PerformForceRestart(appInfo))
                                    }
                                } else {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = "package:${appInfo.app.packageName}".toUri()
                                    }
                                    context.startActivity(intent)
                                }
                            })
                            actions.add(stringResource(R.string.manage_app_info) to {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:${appInfo.app.packageName}".toUri()
                                }
                                context.startActivity(intent)
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
                                    AccessibleMenuItem(
                                        text = text,
                                        onClick = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                            showDropdown.value = false
                                            action()
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
}

@Composable
fun AppManageFab(
    navigator: Navigator,
    modifier: Modifier = Modifier
) {
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
        OverlayDialog(
            title = stringResource(R.string.patch_select_dir_title),
            summary = stringResource(R.string.patch_select_dir_text),
            show = shouldSelectDirectory.value,
            onDismissRequest = { shouldSelectDirectory.value = false },
        ) {
            DialogButtonBar(
                negative = DialogButtonBarAction(
                    text = stringResource(android.R.string.cancel),
                    onClick = { shouldSelectDirectory.value = false },
                ),
                positive = DialogButtonBarAction(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
                        shouldSelectDirectory.value = false
                    },
                ),
            )
        }
    }

    if (showNewPatchDialog.value) {
        OverlayDialog(
            title = stringResource(R.string.screen_new_patch),
            show = showNewPatchDialog.value,
            onDismissRequest = { showNewPatchDialog.value = false },
        ) {
            DialogButtonBar(
                neutral = DialogButtonBarAction(
                    text = stringResource(R.string.patch_from_storage),
                    onClick = {
                        navigator.navigate(Route.NewPatch(id = ACTION_STORAGE))
                        showNewPatchDialog.value = false
                    },
                ),
                positive = DialogButtonBarAction(
                    text = stringResource(R.string.patch_from_applist),
                    onClick = {
                        navigator.navigate(Route.NewPatch(id = ACTION_APPLIST))
                        showNewPatchDialog.value = false
                    },
                ),
                negative = DialogButtonBarAction(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showNewPatchDialog.value = false },
                ),
            )
        }
    }

    FloatingActionButton(
        modifier = modifier,
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
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = stringResource(R.string.add),
            tint = COUITheme.colorScheme.onPrimary
        )
    }
}
