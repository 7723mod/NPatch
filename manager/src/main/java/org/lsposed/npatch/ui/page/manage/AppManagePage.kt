package org.lsposed.npatch.ui.page.manage

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardCapslock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.lsposed.npatch.R
import org.lsposed.npatch.BuildConfig
import org.lsposed.npatch.config.ConfigManager
import org.lsposed.npatch.config.Configs
import org.lsposed.npatch.database.entity.Module
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.share.Constants
import org.lsposed.npatch.share.LSPConfig

import org.lsposed.npatch.ui.component.AppItem
import org.lsposed.npatch.ui.page.ACTION_APPLIST
import org.lsposed.npatch.ui.page.ACTION_STORAGE
import org.lsposed.npatch.ui.page.Navigator
import org.lsposed.npatch.ui.page.Route
import org.lsposed.npatch.ui.page.SelectAppsResult
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import org.lsposed.npatch.ui.viewmodel.manage.AppManageViewModel
import org.lsposed.npatch.ui.viewmodel.manage.ModuleManageViewModel
import org.lsposed.npatch.ui.viewstate.ProcessingState
import nkbe.util.NPackageManager
import nkbe.util.ShizukuApi
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.SnackbarResult
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
) {
    val viewModel = viewModel<AppManageViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()

    var scopeApp by rememberSaveable { mutableStateOf("") }
    val uninstallSuccessfully = stringResource(R.string.manage_uninstall_successfully)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                snackbarHost.showSnackbar(uninstallSuccessfully)
                viewModel.dispatch(AppManageViewModel.ViewAction.Refresh)
            }
        }
    }

    // Miuix 风格的处理中弹窗
    val isProcessing = viewModel.updateLoaderState is ProcessingState.Processing || viewModel.optimizeState is ProcessingState.Processing
    if (isProcessing) {
        val showLoading = remember { mutableStateOf(true) }
        SuperDialog(
            title = stringResource(R.string.manage_loading),
            show = showLoading,
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
        is ProcessingState.Processing -> Unit // 上面统一处理了
        is ProcessingState.Done -> {
            val it = viewModel.updateLoaderState as ProcessingState.Done
            val updateSuccessfully = stringResource(R.string.manage_update_loader_successfully)
            val updateFailed = stringResource(R.string.manage_update_loader_failed)
            val copyError = stringResource(R.string.copy_error)
            LaunchedEffect(Unit) {
                it.result.onSuccess {
                    snackbarHost.showSnackbar(updateSuccessfully)
                }.onFailure {
                    val result = snackbarHost.showSnackbar(updateFailed, copyError)
                    if (result == SnackbarResult.ActionPerformed) {
                        val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("NPatch", it.toString()))
                    }
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
                snackbarHost.showSnackbar(if (it.result) optimizeSucceed else optimizeFailed)
                viewModel.dispatch(AppManageViewModel.ViewAction.ClearOptimizeResult)
            }
        }
    }

    PullToRefresh(
        isRefreshing = viewModel.isRefreshing,
        onRefresh = { viewModel.dispatch(AppManageViewModel.ViewAction.Refresh) },
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
                            text = stringResource(R.string.manage_no_apps),
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
                ) { (appInfo, patchConfig) ->
                    val isRolling = patchConfig.useManager && patchConfig.lspConfig.VERSION_CODE >= Constants.MIN_ROLLING_VERSION_CODE
                    val canUpdateLoader = !isRolling && (patchConfig.lspConfig.VERSION_CODE < LSPConfig.instance.VERSION_CODE || patchConfig.managerPackageName != BuildConfig.APPLICATION_ID)

                    val showDropdown = remember { mutableStateOf(false) }
                    val hapticFeedback = LocalHapticFeedback.current

                    Box(modifier = Modifier.fillMaxWidth()) {
                        AppItem(
                            modifier = Modifier.clickable {
                                showDropdown.value = true
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            icon = NPackageManager.getIcon(appInfo),
                            label = appInfo.label,
                            packageName = appInfo.app.packageName,
                            additionalContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val patchText = if (patchConfig.useManager) stringResource(R.string.patch_local) else stringResource(R.string.patch_integrated)
                                    val patchColor = MiuixTheme.colorScheme.secondary
                                    val versionText = if (isRolling) stringResource(R.string.manage_rolling) else patchConfig.lspConfig.VERSION_CODE.toString()

                                    Text(
                                        text = "$patchText  $versionText",
                                        color = patchColor,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Serif,
                                        style = MiuixTheme.textStyles.footnote1
                                    )
                                    if (canUpdateLoader) {
                                        with(LocalDensity.current) {
                                            val size = MiuixTheme.textStyles.footnote1.fontSize * 1.2
                                            Icon(Icons.Filled.KeyboardCapslock, null, Modifier.size(size.toDp()))
                                        }
                                    }
                                }
                            }
                        )

                        SuperListPopup(
                            show = showDropdown,
                            alignment = PopupPositionProvider.Align.End, // 自动右对齐
                            onDismissRequest = { showDropdown.value = false }
                        ) {
                            val actions = mutableListOf<Pair<String, () -> Unit>>()

                            if (canUpdateLoader || BuildConfig.DEBUG) {
                                actions.add(stringResource(R.string.manage_update_loader) to {
                                    scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.UpdateLoader(appInfo, patchConfig)) }
                                })
                            }
                            if (patchConfig.useManager) {
                                actions.add(stringResource(R.string.manage_module_scope) to {
                                    scope.launch {
                                        scopeApp = appInfo.app.packageName
                                        val activated = ConfigManager.getModulesForApp(scopeApp).map { it.pkgName }.toSet()
                                        val initialSelected = NPackageManager.appList.mapNotNull {
                                            if (activated.contains(it.app.packageName)) it.app.packageName else null
                                        }
                                        val result = navigator.navigateForResult<SelectAppsResult>(
                                            Route.SelectApps(true, initialSelected)
                                        )
                                        if (result is SelectAppsResult.MultipleApps) {
                                            ConfigManager.getModulesForApp(scopeApp).forEach {
                                                ConfigManager.deactivateModule(scopeApp, it)
                                            }
                                            result.selected.forEach {
                                                Log.d(TAG, "Activate ${it.app.packageName} for $scopeApp")
                                                ConfigManager.activateModule(scopeApp, Module(it.app.packageName, it.app.sourceDir))
                                            }
                                        }
                                    }
                                })
                            }
                            val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
                            actions.add(stringResource(R.string.manage_optimize) to {
                                scope.launch {
                                    if (!ShizukuApi.isPermissionGranted) {
                                        snackbarHost.showSnackbar(shizukuUnavailable)
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
    val snackbarHost = LocalSnackbarHost.current
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
            scope.launch { snackbarHost.showSnackbar(errorText) }
        }
    }

    if (shouldSelectDirectory.value) {
        SuperDialog(
            title = stringResource(R.string.patch_select_dir_title),
            show = shouldSelectDirectory,
            onDismissRequest = { shouldSelectDirectory.value = false },
        ) {
            Column {
                Text(
                    text = stringResource(R.string.patch_select_dir_text),
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
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
            show = showNewPatchDialog,
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
