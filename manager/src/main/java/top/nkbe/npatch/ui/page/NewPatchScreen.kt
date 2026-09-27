package top.nkbe.npatch.ui.page

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog

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
    val scrollBehavior = COUIScrollBehavior()
    val context = LocalContext.current
    val activityScope = (context as ComponentActivity).lifecycleScope
    val scope = rememberCoroutineScope()
    val errorUnknown = stringResource(R.string.error_unknown)
    val showSelectModuleDialog = remember { mutableStateOf(false) }
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
            NeoPackageManager.getAppInfoFromApks(apks)
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
                        NeoPackageManager.getAppInfoFromApks(listOf(uri)).onSuccess {
                            viewModel.dispatch(ViewAction.ConfigurePatch(it.first()))
                        }.onFailure {
                            snackbarHost.showSnackbar(it.message ?: errorUnknown)
                            viewModel.reset()
                            navigator.pop()
                        }
                    }
                }
                viewModel.dispatch(ViewAction.DoneInit)
            }
        }
    }

    // 返回鍵攔截
    BackHandler(enabled = true) {
        if (viewModel.patchState != PatchState.PATCHING) {
            scope.launch { NeoPackageManager.cleanTmpApkDir() }
            viewModel.reset()
            navigator.pop()
        }
    }

    // 主體 UI 結構
    NPatchScaffold(
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
            when (viewModel.patchState) {
                PatchState.CONFIGURING -> {
                    PatchOptionsBody(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        onAddEmbed = {
                            showSelectModuleDialog.value = true
                        }
                    )
                }
                PatchState.PATCHING,
                PatchState.FINISHED,
                PatchState.ERROR -> {
                    DoPatchBody(modifier = Modifier, navigator = navigator)
                }
                else -> {}
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
        }
    }

}
