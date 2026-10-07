package top.nkbe.npatch.ui.page

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.page.newpatch.ConfiguringFab
import top.nkbe.npatch.ui.page.newpatch.ConfiguringTopBar
import top.nkbe.npatch.ui.page.newpatch.DoPatchBody
import top.nkbe.npatch.ui.page.newpatch.PatchOptionsBody
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import top.nkbe.npatch.ui.page.SelectAppsResult
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.nkbe.npatch.ui.component.DialogButtonBar
import top.nkbe.npatch.ui.component.DialogButtonBarAction
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.nkbe.npatch.ui.component.OverlayLoadingDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val activityScope = (context as ComponentActivity).lifecycleScope
    val scope = rememberCoroutineScope()
    val errorUnknown = stringResource(R.string.error_unknown)
    val showSelectModuleDialog = remember { mutableStateOf(false) }
    var pendingPatchedApp by remember { mutableStateOf<nkbe.util.NeoPackageManager.AppInfo?>(null) }
    var isExtracting by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var missingOriginalDialog by remember { mutableStateOf<nkbe.util.NeoPackageManager.AppInfo?>(null) }
    var packageMismatchDialog by remember { mutableStateOf<Pair<nkbe.util.NeoPackageManager.AppInfo, nkbe.util.NeoPackageManager.ExtractResult.PackageMismatch>?>(null) }
    var multiAppCandidates by remember { mutableStateOf<List<nkbe.util.NeoPackageManager.AppInfo>?>(null) }
    var parseErrorMessage by remember { mutableStateOf<String?>(null) }

    fun handleAppSelected(app: nkbe.util.NeoPackageManager.AppInfo) {
        var patchedType = app.patchedType
        if (patchedType == nkbe.util.NeoPackageManager.PatchedType.NONE) {
            patchedType = NeoPackageManager.detectPatchedTypeDeep(app)
        }
        if (patchedType != nkbe.util.NeoPackageManager.PatchedType.NONE) {
            pendingPatchedApp = app
        } else {
            viewModel.dispatch(ViewAction.ConfigurePatch(app))
        }
    }

    val apkMimeTypes = arrayOf(
        "application/vnd.android.package-archive",
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    // 從儲存空間選取 APK
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
        if (apks.isEmpty()) {
            viewModel.reset()
            navigator.pop()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            isAnalyzing = true
            NeoPackageManager.getAppInfoFromApks(apks)
                .onSuccess {
                    isAnalyzing = false
                    if (it.size > 1) {
                        multiAppCandidates = it
                    } else if (it.isNotEmpty()) {
                        handleAppSelected(it.first())
                    } else {
                        parseErrorMessage = errorUnknown
                    }
                }
                .onFailure { error ->
                    isAnalyzing = false
                    parseErrorMessage = error.localizedMessage ?: error.message ?: errorUnknown
                }
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.hasExecutedIntent) return@LaunchedEffect
        viewModel.hasExecutedIntent = true
        NeoPackageManager.cleanTmpApkDir()
        when (id) {
            ACTION_STORAGE -> {
                storageLauncher.launch(apkMimeTypes)
                viewModel.dispatch(ViewAction.DoneInit)
            }
            ACTION_APPLIST -> {
                if (!data.isNullOrEmpty()) {
                    val targetApp = NeoPackageManager.appList.firstOrNull { it.app.packageName == data }
                    if (targetApp != null) {
                        handleAppSelected(targetApp)
                    } else {
                        activityScope.launch {
                            val result = navigator.navigateForResult<SelectAppsResult>(Route.SelectApps(false, null))
                            if (result == null) {
                                viewModel.reset()
                                navigator.pop()
                            } else {
                                val singleApp = result as SelectAppsResult.SingleApp
                                handleAppSelected(singleApp.selected)
                            }
                        }
                    }
                } else {
                    activityScope.launch {
                        val result = navigator.navigateForResult<SelectAppsResult>(Route.SelectApps(false, null))
                        if (result == null) {
                            viewModel.reset()
                            navigator.pop()
                        } else {
                            val singleApp = result as SelectAppsResult.SingleApp
                            handleAppSelected(singleApp.selected)
                        }
                    }
                }
                viewModel.dispatch(ViewAction.DoneInit)
            }
            ACTION_INTENT_INSTALL -> {
                data?.let { dataStr ->
                    val uri = dataStr.toUri()
                    scope.launch {
                        isAnalyzing = true
                        NeoPackageManager.getAppInfoFromApks(listOf(uri)).onSuccess {
                            isAnalyzing = false
                            if (it.size > 1) {
                                multiAppCandidates = it
                            } else if (it.isNotEmpty()) {
                                handleAppSelected(it.first())
                            } else {
                                parseErrorMessage = errorUnknown
                            }
                        }.onFailure { error ->
                            isAnalyzing = false
                            parseErrorMessage = error.localizedMessage ?: error.message ?: errorUnknown
                        }
                    }
                }
                viewModel.dispatch(ViewAction.DoneInit)
            }
        }
    }

    // 暫存檔清理挪到 onDispose，不管怎麼離開都會跑到。
    val backEventState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backEventState,
        isBackEnabled = viewModel.patchState == PatchState.PATCHING,
        onBackCompleted = {},
    )

    DisposableEffect(Unit) {
        onDispose {
            viewModel.reset()
            viewModel.viewModelScope.launch { NeoPackageManager.cleanTmpApkDir() }
        }
    }

    // 主體 UI 結構
    NPatchScaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            when (viewModel.patchState) {
                PatchState.CONFIGURING -> ConfiguringTopBar(scrollBehavior) {
                    scope.launch { NeoPackageManager.cleanTmpApkDir() }
                    viewModel.reset()
                    navigator.pop()
                }
                // 只有当包名匹配，且动作是 添加 或 替换 时才认为是安装成功
                PatchState.PATCHING,
                PatchState.FINISHED,
                PatchState.ERROR -> NPatchTopAppBar(title = viewModel.patchApp.app.packageName, scrollBehavior = scrollBehavior)
                else -> NPatchTopAppBar(title = "", scrollBehavior = scrollBehavior)
            }
        },
        floatingActionButton = {
            if (viewModel.patchState == PatchState.CONFIGURING) {
                ConfiguringFab()
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                viewModel.patchState == PatchState.CONFIGURING -> {
                    PatchOptionsBody(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        onAddEmbed = {
                            showSelectModuleDialog.value = true
                        }
                    )
                }
                viewModel.patchState in listOf(PatchState.PATCHING, PatchState.FINISHED, PatchState.ERROR) -> {
                    DoPatchBody(modifier = Modifier, navigator = navigator)
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            InfiniteProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.manage_loading),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }

            OverlayDialog(
                title = stringResource(R.string.patch_embed_modules),
                show = showSelectModuleDialog.value,
                onDismissRequest = { showSelectModuleDialog.value = false },
                renderInRootScaffold = false,
            ) {
                DialogButtonBar(
                    positive = DialogButtonBarAction(
                        text = stringResource(R.string.patch_from_installed_modules),
                        onClick = {
                            showSelectModuleDialog.value = false
                            activityScope.launch {
                                val result = navigator.navigateForResult<SelectAppsResult>(
                                    Route.SelectApps(true, viewModel.embeddedModules.mapTo(ArrayList()) { it.app.packageName })
                                )
                                if (result is SelectAppsResult.MultipleApps) {
                                    viewModel.embeddedModules = result.selected
                                }
                            }
                        },
                    ),
                    negative = DialogButtonBarAction(
                        text = stringResource(android.R.string.cancel),
                        onClick = { showSelectModuleDialog.value = false },
                    ),
                )
            }

            var lastPendingPatchedApp by remember { mutableStateOf<nkbe.util.NeoPackageManager.AppInfo?>(null) }
            if (pendingPatchedApp != null) {
                lastPendingPatchedApp = pendingPatchedApp
            }
            lastPendingPatchedApp?.let { app ->
                val patchedType = NeoPackageManager.detectPatchedTypeDeep(app)
                val typeName = when (patchedType) {
                    NeoPackageManager.PatchedType.EMBEDDED -> stringResource(R.string.patch_type_embedded_apk)
                    NeoPackageManager.PatchedType.NONE -> "NPatch/LSPatch/FPA"
                    else -> patchedType.displayName
                }
                OverlayDialog(
                    title = stringResource(R.string.patch_extract_original_title, typeName),
                    summary = stringResource(R.string.patch_extract_original_text, typeName),
                    show = pendingPatchedApp != null && !isExtracting,
                    onDismissRequest = {
                        pendingPatchedApp = null
                        viewModel.reset()
                        navigator.pop()
                    },
                    renderInRootScaffold = false,
                ) {
                    DialogButtonBar(
                        positive = DialogButtonBarAction(
                            text = stringResource(R.string.patch_extract_original_confirm),
                            onClick = {
                                isExtracting = true
                                scope.launch {
                                    when (val result = NeoPackageManager.extractOriginalApk(app)) {
                                        is NeoPackageManager.ExtractResult.Success -> {
                                            isExtracting = false
                                            pendingPatchedApp = null
                                            viewModel.dispatch(ViewAction.ConfigurePatch(result.originalAppInfo))
                                        }
                                        is NeoPackageManager.ExtractResult.NoOriginalApk -> {
                                            isExtracting = false
                                            pendingPatchedApp = null
                                            missingOriginalDialog = app
                                        }
                                        is NeoPackageManager.ExtractResult.PackageMismatch -> {
                                            isExtracting = false
                                            pendingPatchedApp = null
                                            packageMismatchDialog = app to result
                                        }
                                        is NeoPackageManager.ExtractResult.Corrupted -> {
                                            isExtracting = false
                                            pendingPatchedApp = null
                                            snackbarHost.showSnackbar(result.message)
                                            viewModel.reset()
                                            navigator.pop()
                                        }
                                        is NeoPackageManager.ExtractResult.Error -> {
                                            isExtracting = false
                                            pendingPatchedApp = null
                                            snackbarHost.showSnackbar(result.message)
                                            viewModel.reset()
                                            navigator.pop()
                                        }
                                    }
                                }
                            }
                        ),
                        neutral = DialogButtonBarAction(
                            text = stringResource(R.string.patch_extract_original_direct),
                            onClick = {
                                pendingPatchedApp = null
                                viewModel.dispatch(ViewAction.ConfigurePatch(app))
                            }
                        ),
                        negative = DialogButtonBarAction(
                            text = stringResource(android.R.string.cancel),
                            onClick = {
                                pendingPatchedApp = null
                                viewModel.reset()
                                navigator.pop()
                            }
                        ),
                    )
                }
            }

            OverlayLoadingDialog(
                text = stringResource(R.string.patch_extract_original_extracting),
                show = isExtracting,
                onDismissRequest = { isExtracting = false }
            )

            var lastMissingOriginalDialog by remember { mutableStateOf<nkbe.util.NeoPackageManager.AppInfo?>(null) }
            if (missingOriginalDialog != null) {
                lastMissingOriginalDialog = missingOriginalDialog
            }
            lastMissingOriginalDialog?.let { app ->
                OverlayDialog(
                    title = stringResource(R.string.patch_extract_original_title, app.label),
                    summary = stringResource(R.string.patch_extract_original_missing),
                    show = missingOriginalDialog != null,
                    onDismissRequest = {
                        missingOriginalDialog = null
                        viewModel.reset()
                        navigator.pop()
                    },
                    renderInRootScaffold = false,
                ) {
                    DialogButtonBar(
                        positive = DialogButtonBarAction(
                            text = stringResource(R.string.patch_extract_original_direct),
                            onClick = {
                                missingOriginalDialog = null
                                viewModel.dispatch(ViewAction.ConfigurePatch(app))
                            }
                        ),
                        negative = DialogButtonBarAction(
                            text = stringResource(android.R.string.cancel),
                            onClick = {
                                missingOriginalDialog = null
                                viewModel.reset()
                                navigator.pop()
                            }
                        ),
                    )
                }
            }

            var lastPackageMismatchDialog by remember { mutableStateOf<Pair<nkbe.util.NeoPackageManager.AppInfo, nkbe.util.NeoPackageManager.ExtractResult.PackageMismatch>?>(null) }
            if (packageMismatchDialog != null) {
                lastPackageMismatchDialog = packageMismatchDialog
            }
            lastPackageMismatchDialog?.let { (app, mismatch) ->
                OverlayDialog(
                    title = stringResource(R.string.patch_extract_original_title, app.label),
                    summary = stringResource(R.string.patch_extract_package_mismatch, mismatch.outerPkg, mismatch.innerPkg),
                    show = packageMismatchDialog != null,
                    onDismissRequest = {
                        packageMismatchDialog = null
                        viewModel.reset()
                        navigator.pop()
                    },
                    renderInRootScaffold = false,
                ) {
                    DialogButtonBar(
                        positive = DialogButtonBarAction(
                            text = stringResource(R.string.patch_extract_original_confirm),
                            onClick = {
                                packageMismatchDialog = null
                                viewModel.dispatch(ViewAction.ConfigurePatch(mismatch.originalAppInfo))
                            }
                        ),
                        neutral = DialogButtonBarAction(
                            text = stringResource(R.string.patch_extract_original_direct),
                            onClick = {
                                packageMismatchDialog = null
                                viewModel.dispatch(ViewAction.ConfigurePatch(app))
                            }
                        ),
                        negative = DialogButtonBarAction(
                            text = stringResource(android.R.string.cancel),
                            onClick = {
                                packageMismatchDialog = null
                                viewModel.reset()
                                navigator.pop()
                            }
                        ),
                    )
                }
            }

            var lastParseErrorMessage by remember { mutableStateOf<String?>(null) }
            if (parseErrorMessage != null) {
                lastParseErrorMessage = parseErrorMessage
            }
            lastParseErrorMessage?.let { errMsg ->
                OverlayDialog(
                    title = stringResource(R.string.patch_parse_failed_title),
                    summary = errMsg,
                    show = parseErrorMessage != null,
                    onDismissRequest = {
                        parseErrorMessage = null
                        viewModel.reset()
                        navigator.pop()
                    },
                    renderInRootScaffold = false,
                ) {
                    DialogButtonBar(
                        positive = DialogButtonBarAction(
                            text = stringResource(android.R.string.ok),
                            onClick = {
                                parseErrorMessage = null
                                viewModel.reset()
                                navigator.pop()
                            },
                        ),
                    )
                }
            }

            var lastMultiAppCandidates by remember { mutableStateOf<List<nkbe.util.NeoPackageManager.AppInfo>?>(null) }
            if (multiAppCandidates != null) {
                lastMultiAppCandidates = multiAppCandidates
            }
            lastMultiAppCandidates?.let { candidates ->
                OverlayDialog(
                    title = stringResource(R.string.patch_select_app_from_multiple),
                    summary = stringResource(R.string.patch_select_app_from_multiple_desc),
                    show = multiAppCandidates != null,
                    onDismissRequest = {
                        multiAppCandidates = null
                        viewModel.reset()
                        navigator.pop()
                    },
                    renderInRootScaffold = false,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        candidates.forEach { candidate ->
                            top.yukonga.miuix.kmp.basic.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                onClick = {
                                    multiAppCandidates = null
                                    handleAppSelected(candidate)
                                }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Image(
                                        bitmap = NeoPackageManager.getIcon(candidate),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = candidate.label,
                                            style = MiuixTheme.textStyles.body1,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = candidate.app.packageName,
                                            style = MiuixTheme.textStyles.body2,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    DialogButtonBar(
                        positive = DialogButtonBarAction(
                            text = stringResource(android.R.string.cancel),
                            onClick = {
                                multiAppCandidates = null
                                viewModel.reset()
                                navigator.pop()
                            },
                        ),
                    )
                }
            }
        }
    }

}
