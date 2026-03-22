package org.lsposed.npatch.ui.page

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nkbe.util.NPackageManager
import nkbe.util.NPackageManager.AppInfo
import nkbe.util.ShizukuApi
import org.lsposed.npatch.R
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.ui.component.SelectionColumn
import org.lsposed.npatch.ui.component.ShimmerAnimation
import org.lsposed.npatch.ui.component.settings.SettingsEditor
import org.lsposed.npatch.ui.util.InstallResultReceiver
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import org.lsposed.npatch.ui.util.checkIsApkFixedByLSP
import org.lsposed.npatch.ui.util.installApk
import org.lsposed.npatch.ui.util.installApks
import org.lsposed.npatch.ui.util.isScrolledToEnd
import org.lsposed.npatch.ui.util.lastItemIndex
import org.lsposed.npatch.ui.util.uninstallApkByPackageName
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val TAG = "NewPatchPage"

const val ACTION_STORAGE = 0
const val ACTION_APPLIST = 1
const val ACTION_INTENT_INSTALL = 2

@Composable
fun NewPatchScreen(
    id: Int,
    data: String? = null
) {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val activityScope = remember { (context as ComponentActivity).lifecycleScope }
    val scope = rememberCoroutineScope()
    val lifecycleScope = LocalLifecycleOwner.current.lifecycleScope
    val errorUnknown = stringResource(R.string.error_unknown)
    val showSelectModuleDialog = remember { mutableStateOf(false) }
    var lastDialogCloseTime by remember { mutableLongStateOf(0L) }

    val closeSelectModuleDialog = {
        showSelectModuleDialog.value = false
        lastDialogCloseTime = android.os.SystemClock.elapsedRealtime()
    }

    DisposableEffect(Unit) {
        onDispose {
            if (viewModel.patchState != PatchState.PATCHING && viewModel.patchState != PatchState.FINISHED) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    NPackageManager.cleanTmpApkDir()
                }
                Log.d(TAG, "Tmp Apk Directory cleaned on dispose.")
            }
            viewModel.reset()
        }
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
        if (apks.isEmpty()) {
            navigator.pop()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            NPackageManager.getAppInfoFromApks(apks)
                .onSuccess {
                    viewModel.dispatch(ViewAction.ConfigurePatch(it.first()))
                }
                .onFailure {
                    snackbarHost.showSnackbar(it.message ?: errorUnknown)
                    viewModel.reset()
                    navigator.pop()
                }
        }
    }

    val noXposedModules = stringResource(R.string.patch_no_xposed_module)
    val storageModuleLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
            if (apks.isEmpty()) {
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                NPackageManager.getAppInfoFromApks(apks).onSuccess { appInfos ->
                    val modules = appInfos.filter { it.isXposedModule }
                    if (modules.isEmpty()) {
                        snackbarHost.showSnackbar(noXposedModules)
                    } else {
                        viewModel.embeddedModules = modules
                    }
                }.onFailure {
                    snackbarHost.showSnackbar(it.message ?: errorUnknown)
                }
            }
        }

    // 處理頁面打開的初始化邏輯
    Log.d(TAG, "PatchState: ${viewModel.patchState}")
    LaunchedEffect(viewModel.patchState) {
        if (viewModel.patchState == PatchState.INIT) {
            NPackageManager.cleanTmpApkDir()
            when (id) {
                ACTION_STORAGE -> {
                    storageLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                    viewModel.dispatch(ViewAction.DoneInit)
                }
                ACTION_APPLIST -> {
                    activityScope.launch {
                        val result = navigator.navigateForResult<SelectAppsResult>(Route.SelectApps(false, null))
                        if (result == null) {
                            viewModel.reset()
                            navigator.pop()
                        } else {
                            val singleApp = result as SelectAppsResult.SingleApp
                            viewModel.dispatch(ViewAction.ConfigurePatch(singleApp.selected))
                        }
                    }
                    viewModel.dispatch(ViewAction.DoneInit)
                }
                ACTION_INTENT_INSTALL -> {
                    data?.let { dataStr ->
                        val uri = dataStr.toUri()
                        scope.launch {
                            NPackageManager.getAppInfoFromApks(listOf(uri)).onSuccess {
                                viewModel.dispatch(ViewAction.ConfigurePatch(it.first()))
                            }.onFailure {
                                snackbarHost.showSnackbar(it.message ?: errorUnknown)
                                viewModel.reset()
                                navigator.pop()
                            }
                        }
                    }
                }
            }
        }
    }

    BackHandler(enabled = true) {
        if (viewModel.patchState != PatchState.PATCHING) {
            viewModel.reset()
            navigator.pop()
        }
    }

    // 將 Scaffold 提取到最外層，保證頁面切換不會黑屏
    Scaffold(
        topBar = {
            when (viewModel.patchState) {
                PatchState.CONFIGURING -> ConfiguringTopBar(scrollBehavior) {
                    viewModel.reset()
                    navigator.pop()
                }
                // 只有当包名匹配，且动作是 添加 或 替换 时才认为是安装成功
                PatchState.PATCHING,
                PatchState.FINISHED,
                PatchState.ERROR -> TopAppBar(title = viewModel.patchApp.app.packageName, scrollBehavior = scrollBehavior)
                else -> TopAppBar(title = "", scrollBehavior = scrollBehavior)
            }
        },
        floatingActionButton = {
            if (viewModel.patchState == PatchState.CONFIGURING) {
                ConfiguringFab()
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (viewModel.patchState) {
                PatchState.CONFIGURING -> {
                    PatchOptionsBody(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        onAddEmbed = {
                            if (android.os.SystemClock.elapsedRealtime() - lastDialogCloseTime > 300) {
                                showSelectModuleDialog.value = true
                            }
                        }
                    )
                }
                PatchState.PATCHING,
                PatchState.FINISHED,
                PatchState.ERROR -> {
                    DoPatchBody(modifier = Modifier, navigator = navigator)
                }
                else -> {
                }
            }
        }
    }

    SuperDialog(
        title = stringResource(R.string.patch_embed_modules),
        show = showSelectModuleDialog,
        onDismissRequest = closeSelectModuleDialog,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                text = stringResource(R.string.patch_from_storage),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    storageModuleLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                    closeSelectModuleDialog()
                },
            )
            TextButton(
                text = stringResource(R.string.patch_from_applist),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    lifecycleScope.launch {
                        val result = navigator.navigateForResult<SelectAppsResult>(
                            Route.SelectApps(true, viewModel.embeddedModules.mapTo(ArrayList()) { it.app.packageName })
                        )
                        if (result is SelectAppsResult.MultipleApps) {
                            viewModel.embeddedModules = result.selected
                        }
                    }
                    closeSelectModuleDialog()
                },
            )
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = stringResource(android.R.string.cancel),
                modifier = Modifier.fillMaxWidth(),
                onClick = closeSelectModuleDialog,
            )
        }
    }
}

@Composable
private fun ConfiguringTopBar(scrollBehavior: ScrollBehavior, onBackClick: () -> Unit) {
    TopAppBar(
        title = stringResource(R.string.screen_new_patch),
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.Outlined.ArrowBack, null)
            }
        }
    )
}

@Composable
private fun ConfiguringFab() {
    val viewModel = viewModel<NewPatchViewModel>()
    FloatingActionButton(
        onClick = { viewModel.dispatch(ViewAction.SubmitPatch) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Outlined.AutoFixHigh, null)
            Text(stringResource(R.string.patch_start))
        }
    }
}

@Composable
private fun sigBypassLvStr(level: Int) = when (level) {
    0 -> stringResource(R.string.patch_sigbypasslv0)
    1 -> stringResource(R.string.patch_sigbypasslv1)
    2 -> stringResource(R.string.patch_sigbypasslv2)
    3 -> stringResource(R.string.patch_sigbypasslv3)
    4 -> stringResource(R.string.patch_sigbypasslv4)
    else -> throw IllegalArgumentException("Invalid sigBypassLv: $level")
}

@Composable
private fun PatchOptionsBody(modifier: Modifier, onAddEmbed: () -> Unit) {
    val viewModel = viewModel<NewPatchViewModel>()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical(),
        contentPadding = PaddingValues(bottom = 84.dp) // 给底部的 FAB 留出间距
    ) {
        item {
            SmallTitle(text = "App Info")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                BasicComponent(
                    title = viewModel.patchApp.label,
                    summary = viewModel.patchApp.app.packageName,
                    startAction = {
                        Icon(
                            imageVector = Icons.Outlined.Android,
                            contentDescription = "App Icon",
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    }
                )
            }
        }

        item {
            SmallTitle(text = stringResource(R.string.patch_mode))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                SelectionColumn {
                    SelectionItem(
                        selected = viewModel.useManager,
                        onClick = { viewModel.useManager = true },
                        icon = Icons.Outlined.Api,
                        title = stringResource(R.string.patch_local),
                        desc = stringResource(R.string.patch_local_desc)
                    )
                    SelectionItem(
                        selected = !viewModel.useManager,
                        onClick = {
                            if (!viewModel.useManager) {
                                onAddEmbed()
                            } else {
                                viewModel.useManager = false
                            }
                        },
                        icon = Icons.Outlined.WorkOutline,
                        title = stringResource(R.string.patch_integrated),
                        desc = stringResource(R.string.patch_integrated_desc),
                        extraContent = {
                            TextButton(
                                text = stringResource(R.string.patch_embed_modules),
                                onClick = onAddEmbed,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    )
                }
            }
        }

        item {
            SmallTitle(text = "高級配置")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            ) {
                SettingsEditor(
                    Modifier.padding(top = 12.dp, bottom = 6.dp),
                    stringResource(R.string.patch_new_package),
                    viewModel.newPackageName,
                    onValueChange = { viewModel.newPackageName = it },
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_debuggable),
                    startAction = {
                        Icon(
                            imageVector = Icons.Outlined.BugReport,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    },
                    checked = viewModel.debuggable,
                    onCheckedChange = { viewModel.debuggable = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_override_version_code),
                    summary = stringResource(R.string.patch_override_version_code_desc),
                    startAction = {
                        Icon(
                            imageVector = Icons.Outlined.Layers,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    },
                    checked = viewModel.overrideVersionCode,
                    onCheckedChange = { viewModel.overrideVersionCode = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_inject_dex),
                    summary = stringResource(R.string.patch_inject_dex_desc),
                    startAction = {
                        Icon(
                            imageVector = Icons.Outlined.Code,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    },
                    checked = viewModel.injectDex,
                    onCheckedChange = { viewModel.injectDex = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_inject_mt_provider),
                    summary = stringResource(R.string.patch_inject_mt_provider_desc),
                    startAction = {
                        Icon(
                            imageVector = Icons.Outlined.AddCard,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    },
                    checked = viewModel.injectProvider,
                    onCheckedChange = { viewModel.injectProvider = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_use_microg),
                    summary = stringResource(R.string.patch_use_microg_desc),
                    startAction = {
                        Icon(
                            imageVector = Icons.Outlined.SdStorage,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    },
                    checked = viewModel.useMicroG,
                    onCheckedChange = { viewModel.useMicroG = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_output_log_to_media),
                    summary = stringResource(R.string.patch_output_log_to_media_desc),
                    startAction = {
                        Icon(
                            imageVector = Icons.Outlined.SdStorage,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onBackground
                        )
                    },
                    checked = viewModel.outputLog,
                    onCheckedChange = { viewModel.outputLog = it }
                )
                val sigBypassLevels = (0..4).map { sigBypassLvStr(it) }
                SuperDropdown(
                    title = stringResource(R.string.patch_sigbypass),
                    items = sigBypassLevels,
                    selectedIndex = viewModel.sigBypassLevel,
                    onSelectedIndexChange = { viewModel.sigBypassLevel = it }
                )
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
private fun DoPatchBody(modifier: Modifier, navigator: Navigator) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 监听应用安装广播
    DisposableEffect(viewModel.patchApp.app.packageName) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                val data = intent.data
                val pkgName = data?.schemeSpecificPart

                // 只有当包名匹配，且动作是 添加 或 替换 时才认为是安装成功
                if (pkgName == viewModel.patchApp.app.packageName) {
                    if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REPLACED) {
                        scope.launch {
                            snackbarHost.showSnackbar(context.getString(R.string.patch_install_successfully))
                            viewModel.reset()
                            navigator.pop()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(Unit) {
        if (viewModel.logs.isEmpty()) {
            viewModel.dispatch(ViewAction.LaunchPatch)
        }
    }

    BoxWithConstraints(modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
        val shellBoxMaxHeight =
            if (viewModel.patchState == PatchState.PATCHING) maxHeight
            else maxHeight - 48.dp - 12.dp
        Column(
            Modifier
                .fillMaxSize()
                .wrapContentHeight()
                .animateContentSize(spring(stiffness = Spring.StiffnessLow))
        ) {
            ShimmerAnimation(enabled = viewModel.patchState == PatchState.PATCHING) {
                ProvideTextStyle(MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)) {
                    val scrollState = rememberLazyListState()
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = shellBoxMaxHeight)
                            .background(MiuixTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                            .clip(RoundedCornerShape(20.dp))
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        overscrollEffect = null
                    ) {
                        items(viewModel.logs) {
                            when (it.first) {
                                Log.DEBUG, Log.INFO -> Text(text = it.second)
                                Log.ERROR -> Text(text = it.second, color = MiuixTheme.colorScheme.error)
                            }
                        }
                    }

                    LaunchedEffect(scrollState.lastItemIndex) {
                        if (scrollState.lastItemIndex != null && !scrollState.isScrolledToEnd) {
                            scrollState.animateScrollToItem(scrollState.lastItemIndex!!)
                        }
                    }
                }
            }

            when (viewModel.patchState) {
                PatchState.FINISHED -> {
                    val installFailed = stringResource(R.string.patch_install_failed)
                    val copyError = stringResource(R.string.copy_error)
                    var installation by remember { mutableStateOf<NewPatchViewModel.InstallMethod?>(null) }

                    val onFinish: (Int, String?) -> Unit = { status, message ->
                        scope.launch {
                            if (status == PackageInstaller.STATUS_SUCCESS) {
                                Log.i(TAG, "Install reported success, waiting for broadcast to navigate.")
                            } else if (status != NPackageManager.STATUS_USER_CANCELLED) {
                                // 安装失败处理
                                val result = snackbarHost.showSnackbar(installFailed, copyError)
                                if (result == SnackbarResult.ActionPerformed) {
                                    val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("NPatch", message))
                                }
                            }
                            installation = null // Reset installation state
                        }
                    }
                    when (installation) {
                        NewPatchViewModel.InstallMethod.SYSTEM -> InstallDialog2(viewModel.patchApp, onFinish)
                        NewPatchViewModel.InstallMethod.SHIZUKU -> InstallDialog(viewModel.patchApp, onFinish)
                        null -> {}
                    }
                    Row(Modifier.padding(top = 12.dp)) {
                        TextButton(
                            text = stringResource(R.string.patch_return),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.reset()
                                navigator.pop()
                            },
                        )
                        Spacer(Modifier.weight(0.2f))
                        TextButton(
                            text = stringResource(R.string.install),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                installation = if (!ShizukuApi.isPermissionGranted) NewPatchViewModel.InstallMethod.SYSTEM else NewPatchViewModel.InstallMethod.SHIZUKU
                                Log.d(TAG, "Installation method: $installation")
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                PatchState.ERROR -> {
                    Row(Modifier.padding(top = 12.dp)) {
                        TextButton(
                            text = stringResource(R.string.patch_return),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                viewModel.reset()
                                navigator.pop()
                            },
                        )
                        Spacer(Modifier.weight(0.2f))
                        TextButton(
                            text = stringResource(R.string.copy_error),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("NPatch", viewModel.logs.joinToString(separator = "\n") { it.second }))
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun UninstallConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    SuperDialog(
        title = stringResource(R.string.uninstall),
        show = show,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column {
            Text(
                text = stringResource(R.string.patch_uninstall_text),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { show.value = false; onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(android.R.string.ok),
                    onClick = { show.value = false; onConfirm() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun InstallDialog(patchApp: AppInfo, onFinish: (Int, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var uninstallFirst by remember { mutableStateOf(ShizukuApi.isPackageInstalledWithoutPatch(patchApp.app.packageName)) }
    var installing by remember { mutableStateOf(0) } // 0: idle, 1: installing, 2: uninstalling

    suspend fun doInstall() {
        Log.i(TAG, "Installing app ${patchApp.app.packageName}")
        installing = 1
        val (status, message) = NPackageManager.install()
        installing = 0
        Log.i(TAG, "Installation end: $status, $message")
        onFinish(status, message)
    }

    LaunchedEffect(uninstallFirst) {
        if (!uninstallFirst && installing == 0) {
            onFinish(NPackageManager.STATUS_USER_CANCELLED, "User cancelled")
            doInstall()
        }
    }

    if (uninstallFirst) {
        UninstallConfirmationDialog(
            onDismiss = { onFinish(NPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
            onConfirm = {
                scope.launch {
                    Log.i(TAG, "Uninstalling app ${patchApp.app.packageName}")
                    installing = 2
                    val (status, message) = NPackageManager.uninstall(patchApp.app.packageName)
                    installing = 0
                    Log.i(TAG, "Uninstallation end: $status, $message")
                    if (status == PackageInstaller.STATUS_SUCCESS) {
                        uninstallFirst = false
                    } else {
                        onFinish(status, message)
                    }
                }
            }
        )
    }

    if (installing != 0) {
        val showInstalling = remember { mutableStateOf(true) }
        SuperDialog(
            title = stringResource(if (installing == 1) R.string.installing else R.string.uninstalling),
            show = showInstalling,
            onDismissRequest = {},
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp).size(48.dp))
            }
        }
    }
}

@Composable
private fun InstallDialog2(patchApp: AppInfo, onFinish: (Int, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var uninstallFirst by remember { mutableStateOf(checkIsApkFixedByLSP(lspApp, patchApp.app.packageName)) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val splitInstallReceiver = remember { InstallResultReceiver() }

    fun doInstall() {
        Log.i(TAG, "Installing app with system installer: ${patchApp.app.packageName}")
        val apkFiles = lspApp.targetApkFiles
        if (apkFiles.isNullOrEmpty()){
            onFinish(PackageInstaller.STATUS_FAILURE, "No target APK files found for installation")
            return
        }
        if (apkFiles.size > 1) {
            scope.launch {
                val success = installApks(lspApp, apkFiles)
                onFinish(
                    if (success) PackageInstaller.STATUS_SUCCESS else PackageInstaller.STATUS_FAILURE,
                    if (success) "Split APKs installed successfully" else "Failed to install split APKs"
                )
            }
        } else  {
            installApk(lspApp, apkFiles.first())
            // For single APK install, the result is typically handled by onActivityResult,
            // but since we are using a receiver for splits, we can unify later if needed.
            // For now, system prompt is the feedback. We might need a better way to track this.
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val intentFilter = IntentFilter(InstallResultReceiver.ACTION_INSTALL_STATUS)
        // Correctly handle receiver registration for different Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(splitInstallReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(splitInstallReceiver, intentFilter)
        }

        onDispose {
            context.unregisterReceiver(splitInstallReceiver)
        }
    }

    LaunchedEffect(uninstallFirst) {
        if (!uninstallFirst) {
            Log.d(TAG, "State changed to install, starting installation via system.")
            doInstall()
            // Since system installer is an Intent, it's fire-and-forget. We can dismiss our UI.
            onFinish(NPackageManager.STATUS_USER_CANCELLED, "Handed over to system installer")
        }
    }

    if (uninstallFirst) {
        UninstallConfirmationDialog(
            onDismiss = { onFinish(NPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
            onConfirm = {
                scope.launch {
                    Log.i(TAG, "Uninstalling app ${patchApp.app.packageName}")
                    uninstallApkByPackageName(lspApp, patchApp.app.packageName)
                    // After uninstall intent is sent, we can assume it will proceed.
                    uninstallFirst = false
                }
            }
        )
    }
}
