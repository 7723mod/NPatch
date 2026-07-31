package top.nkbe.npatch.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nkbe.util.ShizukuApi
import top.nkbe.npatch.R
import top.nkbe.npatch.share.LSPConfig
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.viewmodel.manage.AppManageViewModel
import top.nkbe.npatch.ui.viewmodel.manage.ModuleManageViewModel
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.PressFeedbackType
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

@SuppressLint("ContextCastToActivity")
@Composable
fun HomeScreen(
    navigator: Navigator,
    onManageShortcut: (Int) -> Unit = {},
) {
    val scrollBehavior = COUIScrollBehavior()
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

    NPatchScaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior
            )
        }
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
                StatusCard(onManageShortcut)
            }

            item {
                InfoCard()
            }

            item {
                SupportCard(navigator)
            }
        }
    }
}

private val listener: (Int, Int) -> Unit = { _, grantResult ->
    ShizukuApi.isPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
    ShizukuApi.refreshState()
}

@Composable
private fun StatusCard(onManageShortcut: (Int) -> Unit) {
    LaunchedEffect(Unit) {
        ShizukuApi.refreshState()
        ShizukuApi.addRequestPermissionResultListener(listener)
    }
    DisposableEffect(Unit) {
        onDispose {
            ShizukuApi.removeRequestPermissionResultListener(listener)
        }
    }

    val isGranted = ShizukuApi.isPermissionGranted
    val warningContainer = if (COUITheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFFE08A)
    } else {
        Color(0xFF5C4800)
    }
    val warningContent = if (COUITheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF5A4300)
    } else {
        Color(0xFFFFF1BF)
    }
    val containerColor = if (isGranted) COUITheme.colorScheme.primaryContainer else warningContainer
    val contentColor = if (isGranted) COUITheme.colorScheme.onPrimaryContainer else warningContent

    val appViewModel = viewModel<AppManageViewModel>()
    val moduleViewModel = viewModel<ModuleManageViewModel>()
    val appsCount = appViewModel.appList.size
    val modulesCount = moduleViewModel.appList.size

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .height(IntrinsicSize.Min), // 让左右两边高度完美对齐
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            colors = backgroundAwareCardColors(color = containerColor),
            onClick = {
                if (ShizukuApi.isBinderAvailable && !isGranted) {
                    ShizukuApi.requestPermission()
                }
            },
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Tilt
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(x = 38.dp, y = 45.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Icon(
                        modifier = Modifier.size(170.dp),
                        imageVector = if (isGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        tint = contentColor.copy(alpha = 0.2f),
                        contentDescription = null
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(all = 16.dp)
                ) {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = stringResource(if (isGranted) R.string.shizuku_available else R.string.shizuku_unavailable),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text =
                            ShizukuApi.getVersionOrNull()?.let { "API $it" }
                                ?: stringResource(R.string.home_shizuku_warning),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = contentColor.copy(alpha = 0.8f)
                    )
                    if (!isGranted) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.home_shizuku_optional_summary),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Normal,
                            color = contentColor.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = backgroundAwareCardColors(),
                onClick = {
                    onManageShortcut(0)
                },
                insideMargin = PaddingValues(16.dp),
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.apps),
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = appsCount.toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = COUITheme.colorScheme.onSurface,
                        )
                    }
                    Icon(
                        imageVector = COUIIcons.Regular.Back,
                        contentDescription = null,
                        tint = COUITheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier
                            .graphicsLayer(rotationZ = 180f)
                            .size(18.dp)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = backgroundAwareCardColors(),
                onClick = {
                    onManageShortcut(1)
                },
                insideMargin = PaddingValues(16.dp),
                showIndication = true,
                pressFeedbackType = PressFeedbackType.Sink,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = stringResource(R.string.modules),
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = modulesCount.toString(),
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = COUITheme.colorScheme.onSurface,
                        )
                    }
                    Icon(
                        imageVector = COUIIcons.Regular.Back,
                        contentDescription = null,
                        tint = COUITheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier
                            .graphicsLayer(rotationZ = 180f)
                            .size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 10.dp) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = title,
            fontSize = COUITheme.textStyles.headline1.fontSize,
            color = COUITheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = content,
            fontSize = COUITheme.textStyles.body2.fontSize,
            color = COUITheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding)
        )
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

    val copySuccessMessage = stringResource(R.string.home_info_copied)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = backgroundAwareCardColors(),
        onClick = {
            val contentString = listOf(
                "API Version: ${LSPConfig.instance.API_CODE}",
                "NPatch Version: ${LSPConfig.instance.VERSION_NAME} (${LSPConfig.instance.VERSION_CODE})",
                "Framework Version: ${LSPConfig.instance.CORE_VERSION_NAME} (${LSPConfig.instance.CORE_VERSION_CODE})",
                "System Version: $apiVersion",
                "Device: $deviceName",
                "System ABI: ${Build.SUPPORTED_ABIS[0]}"
            ).joinToString("\n")
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("NPatch Info", contentString))
            scope.launch { snackbarHost.showSnackbar(copySuccessMessage) }
        },
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            InfoText(
                title = stringResource(R.string.home_api_version),
                content = "${LSPConfig.instance.API_CODE}"
            )
            InfoText(
                title = stringResource(R.string.home_npatch_version),
                content = "${LSPConfig.instance.VERSION_NAME} (${LSPConfig.instance.VERSION_CODE})"
            )
            InfoText(
                title = stringResource(R.string.home_framework_version),
                content = "${LSPConfig.instance.CORE_VERSION_NAME} (${LSPConfig.instance.CORE_VERSION_CODE})"
            )
            InfoText(
                title = stringResource(R.string.home_system_version),
                content = apiVersion
            )
            InfoText(
                title = stringResource(R.string.home_device),
                content = deviceName
            )
            InfoText(
                title = stringResource(R.string.home_system_abi),
                content = Build.SUPPORTED_ABIS[0],
                bottomPadding = 0.dp
            )
        }
    }
}

@Composable
private fun SupportCard(navigator: Navigator) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        colors = backgroundAwareCardColors(),
    ) {
        Column {
            ArrowPreference(
                title = stringResource(R.string.home_about),
                summary = stringResource(R.string.home_description),
                startAction = {
                    Icon(
                        Icons.Outlined.Info,
                        modifier = Modifier.padding(end = 6.dp),
                        contentDescription = null,
                        tint = COUITheme.colorScheme.onBackground
                    )
                },
                onClick = {
                    navigator.navigate(Route.About)
                }
            )
        }
    }
}
