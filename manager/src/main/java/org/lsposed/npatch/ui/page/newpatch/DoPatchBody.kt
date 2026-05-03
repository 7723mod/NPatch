package org.lsposed.npatch.ui.page.newpatch

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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import nkbe.util.ShizukuApi
import org.lsposed.npatch.R
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.ui.component.ShimmerAnimation
import org.lsposed.npatch.ui.page.Navigator
import org.lsposed.npatch.ui.util.InstallResultReceiver
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import org.lsposed.npatch.ui.util.backgroundAwareCardColors
import org.lsposed.npatch.ui.util.checkIsApkFixedByLSP
import org.lsposed.npatch.ui.util.installApk
import org.lsposed.npatch.ui.util.installApks
import org.lsposed.npatch.ui.util.isScrolledToEnd
import org.lsposed.npatch.ui.util.lastItemIndex
import org.lsposed.npatch.ui.util.uninstallApkByPackageName
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val TAG = "NewPatchPage"

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun DoPatchBody(modifier: Modifier, navigator: Navigator) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 監聽應用安裝廣播
    DisposableEffect(viewModel.patchApp.app.packageName) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                val data = intent.data
                val pkgName = data?.schemeSpecificPart

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
    ) {
        // ── 狀態指示 ──
        AnimatedVisibility(
            visible = viewModel.patchState != PatchState.PATCHING,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = backgroundAwareCardColors(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(
                        imageVector = if (viewModel.patchState == PatchState.FINISHED)
                            Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = if (viewModel.patchState == PatchState.FINISHED)
                            MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = if (viewModel.patchState == PatchState.FINISHED)
                                stringResource(R.string.patch_start) + " ✓"
                            else
                                stringResource(R.string.copy_error),
                            style = MiuixTheme.textStyles.headline1,
                        )
                        Text(
                            text = viewModel.patchApp.app.packageName,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        // ── 進度指示（打包中）──
        AnimatedVisibility(
            visible = viewModel.patchState == PatchState.PATCHING,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = backgroundAwareCardColors(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.patch_start) + "…",
                            style = MiuixTheme.textStyles.headline1,
                        )
                        Text(
                            text = viewModel.patchApp.app.packageName,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        // ── 日誌輸出區域 ──
        SmallTitle(text = "Log")
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 12.dp),
            colors = backgroundAwareCardColors(),
        ) {
            ShimmerAnimation(enabled = viewModel.patchState == PatchState.PATCHING) {
                ProvideTextStyle(MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)) {
                    val scrollState = rememberLazyListState()
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
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
        }

        // ── 底部操作按鈕 ──
        when (viewModel.patchState) {
            PatchState.FINISHED -> {
                val installFailed = stringResource(R.string.patch_install_failed)
                val copyError = stringResource(R.string.copy_error)
                var installation by remember { mutableStateOf<NewPatchViewModel.InstallMethod?>(null) }

                val onFinish: (Int, String?) -> Unit = { status, message ->
                    scope.launch {
                        if (status == PackageInstaller.STATUS_SUCCESS) {
                            Log.i(TAG, "Install reported success, waiting for broadcast to navigate.")
                        } else if (status != NeoPackageManager.STATUS_USER_CANCELLED) {
                            val result = snackbarHost.showSnackbar(installFailed, copyError)
                            if (result == SnackbarResult.ActionPerformed) {
                                val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("NPatch", message))
                            }
                        }
                        installation = null
                    }
                }
                when (installation) {
                    NewPatchViewModel.InstallMethod.SYSTEM -> InstallDialog2(viewModel.patchApp, onFinish)
                    NewPatchViewModel.InstallMethod.SHIZUKU -> InstallDialog(viewModel.patchApp, onFinish)
                    null -> {}
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.patch_return),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.reset()
                            navigator.pop()
                        },
                    )
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.patch_return),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.reset()
                            navigator.pop()
                        },
                    )
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

@Composable
fun UninstallConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    OverlayDialog(
        title = stringResource(R.string.uninstall),
        show = show.value,
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
fun InstallDialog(patchApp: AppInfo, onFinish: (Int, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var uninstallFirst by remember { mutableStateOf(ShizukuApi.isPackageInstalledWithoutPatch(patchApp.app.packageName)) }
    var installing by remember { mutableStateOf(0) }

    suspend fun doInstall() {
        Log.i(TAG, "Installing app ${patchApp.app.packageName}")
        installing = 1
        val (status, message) = NeoPackageManager.install()
        installing = 0
        Log.i(TAG, "Installation end: $status, $message")
        onFinish(status, message)
    }

    LaunchedEffect(uninstallFirst) {
        if (!uninstallFirst && installing == 0) {
            onFinish(NeoPackageManager.STATUS_USER_CANCELLED, "User cancelled")
            doInstall()
        }
    }

    if (uninstallFirst) {
        UninstallConfirmationDialog(
            onDismiss = { onFinish(NeoPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
            onConfirm = {
                scope.launch {
                    Log.i(TAG, "Uninstalling app ${patchApp.app.packageName}")
                    installing = 2
                    val (status, message) = NeoPackageManager.uninstall(patchApp.app.packageName)
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
        OverlayDialog(
            title = stringResource(if (installing == 1) R.string.installing else R.string.uninstalling),
            show = showInstalling.value,
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
fun InstallDialog2(patchApp: AppInfo, onFinish: (Int, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var uninstallFirst by remember { mutableStateOf(checkIsApkFixedByLSP(lspApp, patchApp.app.packageName)) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val splitInstallReceiver = remember { InstallResultReceiver() }

    fun doInstall() {
        Log.i(TAG, "Installing app with system installer: ${patchApp.app.packageName}")
        val apkFiles = lspApp.targetApkFiles
        if (apkFiles.isNullOrEmpty()) {
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
        } else {
            installApk(lspApp, apkFiles.first())
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val intentFilter = IntentFilter(InstallResultReceiver.ACTION_INSTALL_STATUS)
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
            onFinish(NeoPackageManager.STATUS_USER_CANCELLED, "Handed over to system installer")
        }
    }

    if (uninstallFirst) {
        UninstallConfirmationDialog(
            onDismiss = { onFinish(NeoPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
            onConfirm = {
                scope.launch {
                    Log.i(TAG, "Uninstalling app ${patchApp.app.packageName}")
                    uninstallApkByPackageName(lspApp, patchApp.app.packageName)
                    uninstallFirst = false
                }
            }
        )
    }
}
