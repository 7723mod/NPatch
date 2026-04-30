package org.lsposed.npatch.ui.page

import android.util.Log
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
import nkbe.util.NPackageManager
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.page.newpatch.ConfiguringFab
import org.lsposed.npatch.ui.page.newpatch.ConfiguringTopBar
import org.lsposed.npatch.ui.page.newpatch.DoPatchBody
import org.lsposed.npatch.ui.page.newpatch.PatchOptionsBody
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import org.lsposed.npatch.ui.page.SelectAppsResult
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperDialog

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
    val activityScope = (context as ComponentActivity).lifecycleScope
    val scope = rememberCoroutineScope()
    val errorUnknown = stringResource(R.string.error_unknown)
    val showSelectModuleDialog = remember { mutableStateOf(false) }

    // 頁面離開時清理暫存
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

    // 從儲存空間選取 APK
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
        if (apks.isEmpty()) {
            viewModel.reset()
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

    // 從儲存空間選取內嵌模組
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

    Log.d(TAG, "PatchState: ${viewModel.patchState}")
    when (viewModel.patchState) {
        PatchState.INIT -> {
            LaunchedEffect(Unit) {
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
                        viewModel.dispatch(ViewAction.DoneInit)
                    }
                }
            }
        }
        else -> Unit
    }

    // 返回鍵攔截
    BackHandler(enabled = true) {
        if (viewModel.patchState != PatchState.PATCHING) {
            viewModel.reset()
            navigator.pop()
        }
    }

    // 主體 UI 結構
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
                            Log.d(TAG, "onAddEmbed clicked! showSelectModuleDialog was ${showSelectModuleDialog.value}")
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

            if (showSelectModuleDialog.value) {
                Log.d(TAG, "Entering SuperDialog composition")
                SuperDialog(
                    title = stringResource(R.string.patch_embed_modules),
                    show = showSelectModuleDialog,
                    onDismissRequest = { showSelectModuleDialog.value = false },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = stringResource(R.string.patch_from_storage),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                showSelectModuleDialog.value = false
                                storageModuleLauncher.launch(arrayOf("application/vnd.android.package-archive"))
                            },
                        )
                        TextButton(
                            text = stringResource(R.string.patch_from_applist),
                            modifier = Modifier.fillMaxWidth(),
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
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            text = stringResource(android.R.string.cancel),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { showSelectModuleDialog.value = false },
                        )
                    }
                }
            }
        }
    }

}
