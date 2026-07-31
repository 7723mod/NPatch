package top.nkbe.npatch.ui.page

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nkbe.util.ShizukuApi
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.util.backgroundAwareColor
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme

private val welcomeShizukuListener: (Int, Int) -> Unit = { _, grantResult ->
    ShizukuApi.isPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
    ShizukuApi.refreshState()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(
    reviewMode: Boolean,
    onFinish: () -> Unit,
    onReturn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })
    var storageGranted by remember { mutableStateOf(context.hasStorageAccess()) }
    var appListGranted by remember { mutableStateOf(context.hasAppListAccessDeclaration()) }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        storageGranted = context.hasStorageAccess()
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        storageGranted = context.hasStorageAccess()
        appListGranted = context.hasAppListAccessDeclaration()
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = runCatching {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }.getOrElse {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            settingsLauncher.launch(intent)
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    fun completeWelcome() {
        if (reviewMode) {
            onReturn()
        } else {
            Configs.welcomeSeen = true
            onFinish()
        }
    }

    NPatchScaffold(
        bottomBar = {
            WelcomeBottomBar(
                page = pagerState.currentPage,
                reviewMode = reviewMode,
                permissionsReady = storageGranted && appListGranted,
                onBackOrSkip = {
                    if (reviewMode) {
                        onReturn()
                    } else {
                        Configs.welcomeSeen = true
                        onFinish()
                    }
                },
                onNext = {
                    if (pagerState.currentPage == 2) {
                        completeWelcome()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomeIntroPage()
                1 -> WelcomePermissionPage(
                    storageGranted = storageGranted,
                    appListGranted = appListGranted,
                    onStorageClick = ::requestStorageAccess,
                    onAppListClick = {
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }
                )
                else -> WelcomeDisclaimerPage()
            }
        }
    }
}

@Composable
private fun WelcomeIntroPage() {
    val versionLabel = stringResource(R.string.welcome_version, BuildConfig.VERSION_NAME)
    WelcomePageContainer {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_playstore),
                    contentDescription = null,
                    modifier = Modifier
                        .size(86.dp)
                        .clip(CircleShape)
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = COUITheme.textStyles.title1,
                    fontWeight = FontWeight.SemiBold,
                    color = COUITheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = versionLabel,
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        contentDescription = versionLabel
                    }
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.welcome_intro_content),
                    style = COUITheme.textStyles.body1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.welcome_intro_detail),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Start
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                LanguagePreference()
            }
        }
    }
}

@Composable
private fun WelcomePermissionPage(
    storageGranted: Boolean,
    appListGranted: Boolean,
    onStorageClick: () -> Unit,
    onAppListClick: () -> Unit,
) {
    WelcomePageContainer {
        WelcomePageHeader(
            icon = Icons.Outlined.Security,
            title = stringResource(R.string.welcome_permission_title),
            summary = stringResource(R.string.welcome_permission_summary)
        )
        Spacer(Modifier.height(16.dp))
        PermissionStatusCard(
            icon = Icons.Outlined.Folder,
            title = stringResource(R.string.welcome_permission_storage_title),
            summary = stringResource(R.string.welcome_permission_storage_summary),
            granted = storageGranted,
            onClick = onStorageClick
        )
        Spacer(Modifier.height(12.dp))
        PermissionStatusCard(
            icon = Icons.Outlined.Apps,
            title = stringResource(R.string.welcome_permission_applist_title),
            summary = stringResource(R.string.welcome_permission_applist_summary),
            granted = appListGranted,
            onClick = onAppListClick
        )
        Spacer(Modifier.height(12.dp))
        OptionalFeatureCard()
        Spacer(Modifier.height(4.dp))
        SmallTitle(text = stringResource(R.string.welcome_basic_settings_title))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                AppearanceSettings()
                StorageDirectory()
            }
        }
    }
}

@Composable
private fun OptionalFeatureCard() {
    LaunchedEffect(Unit) {
        ShizukuApi.refreshState()
        ShizukuApi.addRequestPermissionResultListener(welcomeShizukuListener)
    }
    DisposableEffect(Unit) {
        onDispose {
            ShizukuApi.removeRequestPermissionResultListener(welcomeShizukuListener)
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
    val shizukuApiVersion = ShizukuApi.getVersionOrNull()
    val shizukuStatusDescription = shizukuApiVersion?.let {
        stringResource(R.string.home_api_version) + " $it"
    } ?: stringResource(R.string.home_shizuku_warning)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(
            color = containerColor,
            contentColor = contentColor
        ),
        showIndication = true,
        onClick = {
            if (ShizukuApi.isBinderAvailable && !isGranted) {
                ShizukuApi.requestPermission()
            }
        },
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.welcome_optional_title),
                    style = COUITheme.textStyles.title3,
                    color = contentColor
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(if (isGranted) R.string.shizuku_available else R.string.shizuku_unavailable),
                    style = COUITheme.textStyles.body1,
                    color = contentColor.copy(alpha = 0.92f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = shizukuApiVersion?.let { "API $it" }
                        ?: stringResource(R.string.home_shizuku_warning),
                    style = COUITheme.textStyles.body2,
                    color = contentColor.copy(alpha = 0.82f),
                    modifier = Modifier.semantics {
                        contentDescription = shizukuStatusDescription
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.welcome_optional_summary),
                    style = COUITheme.textStyles.body2,
                    fontSize = 13.sp,
                    color = contentColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun WelcomeDisclaimerPage() {
    WelcomePageContainer {
        WelcomePageHeader(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.welcome_disclaimer_title),
            summary = stringResource(R.string.welcome_disclaimer_summary)
        )
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.welcome_disclaimer_content),
                    style = COUITheme.textStyles.body1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun WelcomePageContainer(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun WelcomePageHeader(
    icon: ImageVector,
    title: String,
    summary: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundAwareColor(COUITheme.colorScheme.primaryContainer)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = COUITheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = COUITheme.textStyles.title2,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun PermissionStatusCard(
    icon: ImageVector,
    title: String,
    summary: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(),
        showIndication = !granted,
        onClick = {
            if (!granted) onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = COUITheme.colorScheme.primary
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = COUITheme.textStyles.title3,
                    color = COUITheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.welcome_permission_granted),
                    tint = COUITheme.colorScheme.primary
                )
            } else {
                Text(
                    text = stringResource(R.string.welcome_permission_authorize),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun WelcomeBottomBar(
    page: Int,
    reviewMode: Boolean,
    permissionsReady: Boolean,
    onBackOrSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            text = stringResource(if (reviewMode) R.string.welcome_btn_return else R.string.welcome_btn_skip),
            onClick = onBackOrSkip
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            enabled = page != 1 || permissionsReady,
            colors = ButtonDefaults.buttonColorsPrimary(),
            insideMargin = PaddingValues(horizontal = 22.dp, vertical = 13.dp)
        ) {
            Text(
                text = stringResource(if (page == 2) R.string.welcome_btn_finish else R.string.welcome_btn_next),
                style = COUITheme.textStyles.button
            )
        }
    }
}

private fun Context.hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.hasAppListAccessDeclaration(): Boolean {
    val permissions = packageManager
        .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        .orEmpty()
    return Manifest.permission.QUERY_ALL_PACKAGES in permissions
}
