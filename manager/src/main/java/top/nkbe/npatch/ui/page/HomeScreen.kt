package top.nkbe.npatch.ui.page

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.util.LocalFloatingBottomBarPadding
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.util.backgroundAwareHazeStyle
import top.nkbe.npatch.ui.viewmodel.manage.AppManageViewModel
import top.nkbe.npatch.ui.viewmodel.manage.ModuleManageViewModel
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@SuppressLint("ContextCastToActivity")
@Composable
fun HomeScreen(
    navigator: Navigator,
    onManageShortcut: (Int) -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberHazeState()
    val hazeStyle = backgroundAwareHazeStyle()
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
            NPatchTopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
                hazeState = hazeState,
                hazeStyle = hazeStyle,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp + LocalFloatingBottomBarPadding.current
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
    val appViewModel = viewModel<AppManageViewModel>()
    val moduleViewModel = viewModel<ModuleManageViewModel>()

    val warningColor = Color(0xFFE65100)
    val statusColor = if (isGranted) MiuixTheme.colorScheme.primary else warningColor
    val cardColors = if (isGranted) {
        backgroundAwareCardColors()
    } else {
        CardDefaults.defaultColors(
            color = warningColor.copy(alpha = 0.15f)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = cardColors,
            onClick = {
                if (ShizukuApi.isBinderAvailable && !isGranted) {
                    ShizukuApi.requestPermission()
                }
            },
            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            showIndication = true,
            pressFeedbackType = PressFeedbackType.Sink,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = statusColor.copy(alpha = 0.16f),
                            shape = CircleShape,
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(if (isGranted) R.string.shizuku_available else R.string.shizuku_unavailable),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isGranted) MiuixTheme.colorScheme.onSurface else statusColor,
                    )
                    Text(
                        text = if (isGranted) {
                            ShizukuApi.getVersionOrNull()?.let { "API $it" }.orEmpty()
                        } else {
                            stringResource(R.string.home_shizuku_optional_summary)
                        },
                        fontSize = 13.sp,
                        color = if (isGranted) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        },
                    )
                }

                if (!isGranted && ShizukuApi.isBinderAvailable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = null,
                        tint = statusColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .graphicsLayer(rotationZ = 180f)
                            .size(20.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CountCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.apps),
                count = appViewModel.appList.size,
                onClick = { onManageShortcut(0) },
            )
            CountCard(
                modifier = Modifier.weight(1f),
                title = stringResource(R.string.modules),
                count = moduleViewModel.appList.size,
                onClick = { onManageShortcut(1) },
            )
        }
    }
}

@Composable
private fun CountCard(
    modifier: Modifier,
    title: String,
    count: Int,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        colors = backgroundAwareCardColors(),
        onClick = onClick,
        insideMargin = PaddingValues(16.dp),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = count.toString(),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            Icon(
                imageVector = MiuixIcons.Regular.Back,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .graphicsLayer(rotationZ = 180f)
                    .size(18.dp),
            )
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 10.dp) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = title,
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                },
                onClick = {
                    navigator.navigate(Route.About)
                }
            )
        }
    }
}
