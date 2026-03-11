package org.lsposed.npatch.ui.page

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nkbe.util.ShizukuApi
import org.lsposed.npatch.R
import org.lsposed.npatch.share.LSPConfig
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.core.net.toUri

@Composable
fun HomeScreen(navigator: Navigator) {
    val scrollBehavior = MiuixScrollBehavior()
    var isIntentLaunched by rememberSaveable { mutableStateOf(false) }
    val activity = LocalContext.current as Activity
    val intent = activity.intent

    LaunchedEffect(Unit) {
        if (!isIntentLaunched && intent.action == Intent.ACTION_VIEW && intent.hasCategory(Intent.CATEGORY_DEFAULT) && intent.type == "application/vnd.android.package-archive") {
            isIntentLaunched = true
            val uri = intent.data
            if (uri != null) {
                navigator.navigate(
                    Route.NewPatch(
                        id = ACTION_INTENT_INSTALL,
                        data = uri.toString()
                    )
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = {}
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            overscrollEffect = null
        ) {
            item {
                ShizukuCard()
            }

            item {
                SmallTitle(text = stringResource(R.string.home_device_info))
                InfoCard()
            }

            item {
                SmallTitle(text = stringResource(R.string.home_support))
                SupportCard()
            }
        }
    }
}

private val listener: (Int, Int) -> Unit = { _, grantResult ->
    ShizukuApi.isPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun ShizukuCard() {
    LaunchedEffect(Unit) {
        Shizuku.addRequestPermissionResultListener(listener)
    }
    DisposableEffect(Unit) {
        onDispose {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    val isGranted = ShizukuApi.isPermissionGranted

    val containerColor = if (isGranted) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.errorContainer
    val contentColor = if (isGranted) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onErrorContainer

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = CardDefaults.defaultColors(color = containerColor),
        onClick = {
            if (ShizukuApi.isBinderAvailable && !isGranted) {
                Shizuku.requestPermission(114514)
            }
        },
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
        insideMargin = PaddingValues(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(if (isGranted) R.string.shizuku_available else R.string.shizuku_unavailable),
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = if (isGranted) "API ${Shizuku.getVersion()}" else stringResource(R.string.home_shizuku_warning),
                    style = MiuixTheme.textStyles.body2,
                    color = contentColor.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun InfoCard() {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()

    val apiVersion = if (Build.VERSION.PREVIEW_SDK_INT != 0) {
        "${Build.VERSION.CODENAME} Preview (API ${Build.VERSION.PREVIEW_SDK_INT})"
    } else {
        "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    }

    val deviceName = buildString {
        append(Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
        if (Build.BRAND != Build.MANUFACTURER) {
            append(" " + Build.BRAND.replaceFirstChar { it.uppercase() })
        }
        append(" " + Build.MODEL)
    }

    val infoList = listOf(
        stringResource(R.string.home_api_version) to "${LSPConfig.instance.API_CODE}",
        stringResource(R.string.home_npatch_version) to "${LSPConfig.instance.VERSION_NAME} (${LSPConfig.instance.VERSION_CODE})",
        stringResource(R.string.home_framework_version) to "${LSPConfig.instance.CORE_VERSION_NAME} (${LSPConfig.instance.CORE_VERSION_CODE})",
        stringResource(R.string.home_system_version) to apiVersion,
        stringResource(R.string.home_device) to deviceName,
        stringResource(R.string.home_system_abi) to Build.SUPPORTED_ABIS[0]
    )

    val copySuccessMessage = stringResource(R.string.home_info_copied)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onClick = {
            val contentString = infoList.joinToString("\n") { "${it.first}: ${it.second}" }
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NPatch Info", contentString))
            scope.launch { snackbarHost.showSnackbar(copySuccessMessage) }
        },
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink // Miuix 原生下沉点击动效
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            infoList.forEachIndexed { index, (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = value,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        SuperArrow(
            title = "GitHub",
            summary = stringResource(R.string.home_view_source_code, "GitHub", ""),
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, "https://github.com/7723mod/NPatch".toUri())
                context.startActivity(intent)
            }
        )
        SuperArrow(
            title = "Telegram",
            summary = "Subscribe to our channel",
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, "https://t.me/NPatch".toUri())
                context.startActivity(intent)
            }
        )
    }
}
