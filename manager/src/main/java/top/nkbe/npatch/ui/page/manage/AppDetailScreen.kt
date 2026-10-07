package top.nkbe.npatch.ui.page.manage

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nkbe.util.NeoPackageManager
import nkbe.util.ShizukuApi
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.database.entity.LoadedModule
import top.nkbe.npatch.manager.DiagnosticLogExporter
import top.nkbe.npatch.manager.ModuleScopeSyncStore
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.LSPConfig
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.component.OverlayLoadingDialog
import top.nkbe.npatch.ui.page.ACTION_APPLIST
import top.nkbe.npatch.ui.page.Navigator
import top.nkbe.npatch.ui.page.Route
import top.nkbe.npatch.ui.page.SelectAppsResult
import top.nkbe.npatch.ui.page.newpatch.sigBypassLvTitle
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.viewmodel.manage.AppManageViewModel
import top.nkbe.npatch.ui.viewmodel.manage.ModuleManageViewModel
import top.nkbe.npatch.ui.viewstate.ProcessingState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.RotateLeft
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val TAG = "AppDetailScreen"

/**
 * 應用的二級詳情頁：取代原本長按/點擊後彈出的扁平選單。
 * 把原本 9 個純文字選項分成狀態、操作、系統、危險操作四組，
 * 並直接顯示 Loader 版本、模組數量等訊息，不用點進去才知道。
 */
@Composable
fun AppDetailScreen(
    packageName: String,
    navigator: Navigator,
    onBack: () -> Unit,
) {
    val viewModel = viewModel<AppManageViewModel>()
    val moduleManageViewModel = viewModel<ModuleManageViewModel>()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hapticFeedback = LocalHapticFeedback.current

    val entry = viewModel.appList.firstOrNull { it.first.app.packageName == packageName }

    val uninstallSuccessfully = stringResource(R.string.manage_uninstall_successfully)
    val uninstallLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, uninstallSuccessfully, Toast.LENGTH_SHORT).show()
            viewModel.dispatch(AppManageViewModel.ViewAction.Refresh)
            onBack()
        }
    }

    val isProcessing = viewModel.updateLoaderState is ProcessingState.Processing ||
        viewModel.optimizeState is ProcessingState.Processing ||
        viewModel.forceStopState is ProcessingState.Processing ||
        viewModel.forceRestartState is ProcessingState.Processing
    if (isProcessing) {
        OverlayLoadingDialog(
            text = stringResource(R.string.manage_loading),
            show = true,
            onDismissRequest = { /* 阻斷取消，等待處理完成 */ },
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

    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberHazeState()

    NPatchScaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            NPatchTopAppBar(
                title = stringResource(R.string.manage_app_detail),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            MiuixIcons.Regular.Back,
                            contentDescription = stringResource(R.string.nav_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        if (entry == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                InfiniteProgressIndicator()
            }
            return@NPatchScaffold
        }
        val (appInfo, patchConfig) = entry

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

        var moduleCount by remember(packageName) { mutableStateOf<Int?>(null) }
        val scopeUpdatedText = stringResource(R.string.manage_module_scope_updated)

        LaunchedEffect(packageName) {
            moduleCount = withContext(Dispatchers.IO) { ConfigManager.getModulesForApp(packageName).size }
        }

        val colorScheme = MiuixTheme.colorScheme

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // 頂部卡片：圖示、名稱、套件名與狀態標籤
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(color = colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(16.dp)),
                        ) {
                            Image(
                                bitmap = NeoPackageManager.getIcon(appInfo),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = appInfo.label,
                                fontSize = 19.sp,
                                fontWeight = FontWeight(600),
                                color = colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = appInfo.app.packageName,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val modeLabel = if (isLocal) {
                            "${stringResource(R.string.patch_local)} ${stringResource(R.string.manage_rolling)}"
                        } else {
                            stringResource(R.string.patch_integrated)
                        }
                        DetailChip(text = modeLabel, color = colorScheme.primary)
                        if (showVersionNumber) {
                            DetailChip(text = currentVersion.toString(), color = colorScheme.onSurfaceVariantSummary)
                        }
                        if (canUpdateLoader) {
                            // 固定的語意色（可更新狀態），不隨主題強調色變化
                            DetailChip(text = stringResource(R.string.manage_update_available), color = Color(0xFFB45309))
                        }
                    }
                }
            }

            run {
                Spacer(Modifier.height(16.dp))
                SmallTitle(text = stringResource(R.string.manage_status_section))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = backgroundAwareCardColors(),
                ) {
                    if (isLocal) {
                        ArrowPreference(
                            title = stringResource(R.string.manage_module_scope),
                            summary = moduleCount?.let { pluralStringResource(R.plurals.manage_module_scope_count, it, it) },
                            startAction = { DetailIcon(MiuixIcons.Regular.Layers) },
                            onClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                                scope.launch {
                                    val activated = withContext(Dispatchers.IO) {
                                        ConfigManager.getModulesForApp(packageName).map { it.pkgName }.toSet()
                                    }
                                    val initialSelected = NeoPackageManager.appList.mapNotNull {
                                        if (activated.contains(it.app.packageName)) it.app.packageName else null
                                    }
                                    val result = navigator.navigateForResult<SelectAppsResult>(
                                        Route.SelectApps(true, initialSelected),
                                    )
                                    if (result is SelectAppsResult.MultipleApps) {
                                        withContext(Dispatchers.IO) {
                                            val previousModules = ConfigManager.getModulesForApp(packageName)
                                            val affectedPackages = buildSet {
                                                previousModules.forEach { add(it.pkgName) }
                                                result.selected.forEach { add(it.app.packageName) }
                                            }
                                            previousModules.forEach {
                                                ConfigManager.deactivateModule(packageName, it)
                                            }
                                            result.selected.forEach {
                                                Log.d(TAG, "Activate ${it.app.packageName} for $packageName")
                                                ConfigManager.activateModule(packageName, LoadedModule(it.app.packageName, it.app.sourceDir))
                                            }
                                            // 一併通知被移除與新加入的模組，避免範圍調整後殘留舊狀態（優先 Shizuku，無 Shizuku 則走 ContentProvider）
                                            ModuleScopeSyncStore.syncModuleScopes(affectedPackages)
                                        }
                                        moduleManageViewModel.refreshScopedActivationState()
                                        moduleCount = withContext(Dispatchers.IO) { ConfigManager.getModulesForApp(packageName).size }
                                        Toast.makeText(context, scopeUpdatedText, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                    BasicComponent(
                        title = stringResource(R.string.manage_sigbypass_level),
                        startAction = { DetailIcon(MiuixIcons.Regular.Info) },
                        endActions = {
                            Text(
                                text = if (patchConfig.sigBypassLevel in 0..5) {
                                    "Lv${patchConfig.sigBypassLevel} ${sigBypassLvTitle(patchConfig.sigBypassLevel)}"
                                } else {
                                    "Lv${patchConfig.sigBypassLevel}"
                                },
                                fontSize = 14.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        },
                    )
                    if (canUpdateLoader) {
                        BasicComponent(
                            title = stringResource(R.string.manage_loader_version),
                            startAction = { DetailIcon(MiuixIcons.Regular.Info) },
                            endActions = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$currentVersion → ",
                                        fontSize = 14.sp,
                                        color = colorScheme.onSurfaceVariantSummary,
                                    )
                                    Text(
                                        text = "$managerVersion",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = colorScheme.primary,
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SmallTitle(text = stringResource(R.string.manage_actions_section))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(),
            ) {
                if (canUpdateLoader || BuildConfig.DEBUG) {
                    BasicComponent(
                        title = stringResource(R.string.manage_update_loader),
                        summary = stringResource(R.string.manage_update_loader_desc, managerVersion),
                        startAction = { DetailIcon(MiuixIcons.Regular.Update, tint = colorScheme.primary) },
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                            scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.UpdateLoader(appInfo, patchConfig)) }
                        },
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.manage_repatch),
                    summary = stringResource(R.string.manage_repatch_desc),
                    startAction = { DetailIcon(MiuixIcons.Regular.Refresh) },
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        navigator.navigate(Route.NewPatch(id = ACTION_APPLIST, data = packageName))
                    },
                )
                val shizukuUnavailable = stringResource(R.string.shizuku_unavailable)
                BasicComponent(
                    title = stringResource(R.string.manage_optimize),
                    summary = stringResource(R.string.manage_optimize_desc),
                    startAction = { DetailIcon(MiuixIcons.Regular.Tune) },
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        scope.launch {
                            if (!ShizukuApi.isReady) {
                                Toast.makeText(context, shizukuUnavailable, Toast.LENGTH_SHORT).show()
                            } else {
                                viewModel.dispatch(AppManageViewModel.ViewAction.PerformOptimize(appInfo))
                            }
                        }
                    },
                )
                val exportChooserTitle = stringResource(R.string.manage_export_diagnostics_chooser)
                val exportFailedText = stringResource(R.string.manage_export_diagnostics_failed)
                BasicComponent(
                    title = stringResource(R.string.manage_export_diagnostics),
                    summary = stringResource(R.string.manage_export_diagnostics_desc),
                    startAction = { DetailIcon(MiuixIcons.Regular.Share) },
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        scope.launch {
                            runCatching {
                                val result = DiagnosticLogExporter.export(context, packageName)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    result.file,
                                )
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(context.contentResolver, result.file.name, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }.let { shareIntent ->
                                    context.startActivity(Intent.createChooser(shareIntent, exportChooserTitle))
                                }
                            }.onFailure {
                                Log.e(TAG, "Failed to export diagnostics for $packageName", it)
                                Toast.makeText(context, exportFailedText, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            SmallTitle(text = stringResource(R.string.manage_system_section))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(),
            ) {
                BasicComponent(
                    title = stringResource(R.string.manage_force_stop),
                    summary = stringResource(R.string.manage_force_stop_desc),
                    startAction = { DetailIcon(MiuixIcons.Regular.Pause) },
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (ShizukuApi.isReady) {
                            scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.PerformForceStop(appInfo)) }
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:$packageName".toUri()
                                },
                            )
                        }
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.manage_force_restart),
                    summary = stringResource(R.string.manage_force_restart_desc),
                    startAction = { DetailIcon(MiuixIcons.Regular.RotateLeft) },
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        if (ShizukuApi.isReady) {
                            scope.launch { viewModel.dispatch(AppManageViewModel.ViewAction.PerformForceRestart(appInfo)) }
                        } else {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = "package:$packageName".toUri()
                                },
                            )
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.manage_app_info),
                    startAction = { DetailIcon(MiuixIcons.Regular.Info) },
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:$packageName".toUri()
                            },
                        )
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            SmallTitle(text = stringResource(R.string.manage_danger_section))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(color = colorScheme.error.copy(alpha = 0.08f)),
            ) {
                BasicComponent(
                    title = stringResource(R.string.uninstall),
                    titleColor = BasicComponentDefaults.titleColor(color = colorScheme.error),
                    summary = stringResource(R.string.manage_uninstall_desc),
                    summaryColor = BasicComponentDefaults.summaryColor(color = colorScheme.error.copy(alpha = 0.75f)),
                    startAction = { DetailIcon(MiuixIcons.Regular.Delete, tint = colorScheme.error) },
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                        val intent = Intent(Intent.ACTION_DELETE).apply {
                            data = "package:$packageName".toUri()
                            putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        }
                        runCatching { uninstallLauncher.launch(intent) }
                            .onFailure { Log.e(TAG, "Failed to launch uninstall intent for $packageName", it) }
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DetailIcon(imageVector: ImageVector, tint: Color = MiuixTheme.colorScheme.onBackground) {
    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = tint,
        )
    }
}

@Composable
private fun DetailChip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}
