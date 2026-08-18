package top.nkbe.npatch.ui.page.newpatch

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import nkbe.util.ShizukuApi
import top.nkbe.npatch.R
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.ui.component.ShimmerAnimation
import top.nkbe.npatch.ui.page.Navigator
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.util.checkIsApkFixedByLSP
import top.nkbe.npatch.ui.util.isScrolledToEnd
import top.nkbe.npatch.ui.util.lastItemIndex
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.SnackbarResult
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.overlay.OverlayLoadingDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

private const val TAG = "NewPatchPage"

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun DoPatchBody(modifier: Modifier, navigator: Navigator) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

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
                            COUITheme.colorScheme.primary else COUITheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                    Column {
                        Text(
                            text = if (viewModel.patchState == PatchState.FINISHED)
                                stringResource(R.string.patch_start) + " ✓"
                            else
                                stringResource(R.string.copy_error),
                            style = COUITheme.textStyles.headline1,
                        )
                        Text(
                            text = viewModel.patchApp.app.packageName,
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
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
                colors = backgroundAwareCardColors(COUITheme.colorScheme.surfaceVariant),
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
                            style = COUITheme.textStyles.headline1,
                        )
                        Text(
                            text = viewModel.patchApp.app.packageName,
                            style = COUITheme.textStyles.body2,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
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
                .padding(bottom = 12.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val joinedLogs = viewModel.logs.joinToString(separator = "\n") { it.second }
                        if (joinedLogs.isNotEmpty()) {
                            val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("NPatch Log", joinedLogs))
                            scope.launch { snackbarHost.showSnackbar(context.getString(R.string.home_info_copied)) }
                        }
                    }
                ),
            colors = backgroundAwareCardColors(),
        ) {
            ShimmerAnimation(enabled = viewModel.patchState == PatchState.PATCHING) {
                ProvideTextStyle(COUITheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace)) {
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
                            val line = it.second
                            when (it.first) {
                                Log.DEBUG, Log.INFO -> Text(
                                    text = line,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                )
                                Log.ERROR -> Text(
                                    text = line,
                                    color = COUITheme.colorScheme.error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                )
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
                            installation = null
                            viewModel.reset()
                            navigator.pop()
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.patch_install_successfully),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            installation = null
                        } else if (status != NeoPackageManager.STATUS_USER_CANCELLED) {
                            val result = snackbarHost.showSnackbar(installFailed, copyError)
                            if (result == SnackbarResult.ActionPerformed) {
                                val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("NPatch", message))
                            }
                        }
                        if (installation != null) {
                            installation = null
                        }
                    }
                }
                when (installation) {
                    NewPatchViewModel.InstallMethod.SYSTEM -> InstallDialog(
                        patchApp = viewModel.patchApp,
                        method = NeoPackageManager.InstallMethod.SYSTEM,
                        onFinish = onFinish,
                    )

                    NewPatchViewModel.InstallMethod.SHIZUKU -> InstallDialog(
                        patchApp = viewModel.patchApp,
                        method = NeoPackageManager.InstallMethod.SHIZUKU,
                        onFinish = onFinish,
                    )

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
                            installation =
                                if (!ShizukuApi.isReady) NewPatchViewModel.InstallMethod.SYSTEM
                                else NewPatchViewModel.InstallMethod.SHIZUKU
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
    onConfirm: () -> Unit,
    onIgnoreAndInstall: () -> Unit,
) {
    val show = remember { mutableStateOf(true) }
    OverlayDialog(
        title = stringResource(R.string.uninstall),
        summary = stringResource(R.string.patch_uninstall_text),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
        renderInRootScaffold = false,
    ) {
        DialogButtonBar(
            positive = DialogButtonBarAction(
                text = stringResource(android.R.string.ok),
                onClick = { show.value = false; onConfirm() },
            ),
            neutral = DialogButtonBarAction(
                text = stringResource(R.string.patch_ignore_risk_install),
                onClick = { show.value = false; onIgnoreAndInstall() },
            ),
            negative = DialogButtonBarAction(
                text = stringResource(android.R.string.cancel),
                onClick = { show.value = false; onDismiss() },
            ),
        )
    }
}

@Composable
fun InstallDialog(
    patchApp: AppInfo,
    method: NeoPackageManager.InstallMethod,
    onFinish: (Int, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var uninstallFirst by remember(method, patchApp.app.packageName) {
        mutableStateOf(
            if (method == NeoPackageManager.InstallMethod.SHIZUKU) {
                ShizukuApi.isPackageInstalledWithoutPatch(patchApp.app.packageName)
            } else {
                checkIsApkFixedByLSP(context, patchApp.app.packageName)
            },
        )
    }
    var installing by remember { mutableStateOf(0) }
    var installStarted by remember { mutableStateOf(false) }
    suspend fun doInstall() {
        Log.i(TAG, "Installing ${patchApp.app.packageName} with $method")
        installStarted = true
        installing = 1
        val outcome = NeoPackageManager.install(method)
        installing = 0
        Log.i(TAG, "Installation end: $outcome")
        when (outcome) {
            is NeoPackageManager.InstallOutcome.Completed ->
                onFinish(outcome.status, outcome.message)

            NeoPackageManager.InstallOutcome.PermissionRequired -> {
                installStarted = false
                onFinish(
                    NeoPackageManager.STATUS_USER_CANCELLED,
                    "Package install permission is required; retry after granting it",
                )
            }
        }
    }

    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var checkCount = 0
            var stillInstalled = true
            while (checkCount < 10) {
                if (!checkIsApkFixedByLSP(context, patchApp.app.packageName)) {
                    stillInstalled = false
                    break
                }
                kotlinx.coroutines.delay(300)
                checkCount++
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (stillInstalled) {
                    onFinish(PackageInstaller.STATUS_FAILURE, "Original application was not uninstalled")
                } else {
                    uninstallFirst = false
                    if (!installStarted) {
                        doInstall()
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!uninstallFirst && !installStarted) {
            doInstall()
        }
    }

    if (uninstallFirst) {
        UninstallConfirmationDialog(
            onDismiss = { onFinish(NeoPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
            onIgnoreAndInstall = {
                uninstallFirst = false
                if (!installStarted) {
                    scope.launch {
                        doInstall()
                    }
                }
            },
            onConfirm = {
                if (method == NeoPackageManager.InstallMethod.SHIZUKU) {
                    scope.launch {
                        Log.i(TAG, "Uninstalling app ${patchApp.app.packageName}")
                        installing = 2
                        val (status, message) = NeoPackageManager.uninstall(patchApp.app.packageName)
                        installing = 0
                        Log.i(TAG, "Uninstallation end: $status, $message")
                        if (status == PackageInstaller.STATUS_SUCCESS) {
                            uninstallFirst = false
                            if (!installStarted) {
                                doInstall()
                            }
                        } else {
                            uninstallLauncher.launch(
                                Intent(Intent.ACTION_DELETE).apply {
                                    data = "package:${patchApp.app.packageName}".toUri()
                                },
                            )
                        }
                    }
                } else {
                    uninstallLauncher.launch(
                        Intent(Intent.ACTION_DELETE).apply {
                            data = "package:${patchApp.app.packageName}".toUri()
                        },
                    )
                }
            }
        )
    }

    if (installing != 0) {
        val showInstalling = remember { mutableStateOf(true) }
        OverlayLoadingDialog(
            text = stringResource(if (installing == 1) R.string.installing else R.string.uninstalling),
            show = showInstalling.value,
            onDismissRequest = {},
            renderInRootScaffold = false,
        )
    }
}
