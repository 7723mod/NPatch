package top.nkbe.npatch.ui.page

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BlurCircular
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SettingsBrightness
import android.Manifest
import android.os.Build
import top.nkbe.npatch.install.InstallNotificationHelper
import top.nkbe.npatch.install.ThirdPartyPackageInstaller
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.launch
import top.nkbe.npatch.LSPApplication
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.CARD_BACKGROUND_ALPHA_MAX
import top.nkbe.npatch.config.CARD_BACKGROUND_ALPHA_MIN
import top.nkbe.npatch.config.KeystorePreset
import top.nkbe.npatch.config.MyKeyStore
import top.nkbe.npatch.config.ThemeConfig
import top.nkbe.npatch.config.ThemeMode
import top.nkbe.npatch.config.ThemeSettings
import top.nkbe.npatch.config.dataStore
import top.nkbe.npatch.manager.ManagerCacheCleaner
import top.nkbe.npatch.manager.ManagerLogger
import top.nkbe.npatch.network.DnsProvider
import top.nkbe.npatch.network.NetworkDns
import top.nkbe.npatch.ui.activity.MainActivity
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.config.DEFAULT_CUSTOM_COLOR
import top.nkbe.npatch.config.DEFAULT_CARD_BACKGROUND_ALPHA_PERCENT
import top.nkbe.npatch.ui.util.BackgroundImageStorage
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBar
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Slider
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.basic.TextField
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.util.backgroundAwareHazeStyle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import kotlin.math.roundToInt

private const val TAG = "SettingsScreen"

@Composable
fun SettingsScreen() {
    val scrollBehavior = COUIScrollBehavior()
    val hazeState = rememberHazeState()
    val hazeStyle = backgroundAwareHazeStyle()
    val useFloatingGlassBottomBar = LocalFloatingGlassBottomBar.current
    val bottomContentPadding = if (useFloatingGlassBottomBar) {
        68.dp + 12.dp + 8.dp + 28.dp +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    } else {
        24.dp
    }
    NPatchScaffold(
        topBar = {
            NPatchTopAppBar(
                title = stringResource(R.string.screen_settings),
                scrollBehavior = scrollBehavior,
                hazeState = hazeState,
                hazeStyle = hazeStyle,
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            SmallTitle(text = stringResource(R.string.settings_appearance_theme))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(),
            ) {
                AppearanceSettings()
            }

            Spacer(Modifier.height(12.dp))

            SmallTitle(text = stringResource(R.string.settings_installation_category))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(),
            ) {
                InstallationSettings()
            }

            Spacer(Modifier.height(12.dp))

            SmallTitle(text = stringResource(R.string.settings_network))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(),
            ) {
                DnsPreference()
            }

            Spacer(Modifier.height(12.dp))

            SmallTitle(text = stringResource(R.string.settings_other_settings))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(),
            ) {
                LanguagePreference()
                KeyStore()
                DetailPatchLogs()
                OutputFullLog()
                WelcomeGuide()
                StorageDirectory()
                ClearManagerCache()
            }
            Spacer(Modifier.height(bottomContentPadding))
        }
    }
}

@Composable
private fun DnsPreference() {
    val providers = DnsProvider.entries
    val labels = listOf(
        stringResource(R.string.settings_dns_tencent),
        stringResource(R.string.settings_dns_google),
        stringResource(R.string.settings_dns_cloudflare),
        stringResource(R.string.settings_dns_system),
        stringResource(R.string.settings_dns_custom),
    )
    var selectedProvider by remember { mutableStateOf(NetworkDns.selectedProvider()) }
    var showCustomDialog by remember { mutableStateOf(false) }

    OverlayDropdownPreference(
        title = stringResource(R.string.settings_dns),
        summary = stringResource(R.string.settings_dns_summary),
        items = labels,
        selectedIndex = providers.indexOf(selectedProvider),
        startAction = { SettingsStartIcon(Icons.Outlined.Language) },
        onSelectedIndexChange = { index ->
            val provider = providers[index]
            if (provider == DnsProvider.CUSTOM) {
                showCustomDialog = true
            } else {
                NetworkDns.setProvider(provider)
                selectedProvider = provider
            }
        }
    )

    if (showCustomDialog) {
        var customUrl by rememberSaveable { mutableStateOf(NetworkDns.customUrl()) }
        var invalidUrl by rememberSaveable { mutableStateOf(false) }
        OverlayDialog(
            title = stringResource(R.string.settings_dns_custom),
            show = true,
            onDismissRequest = { showCustomDialog = false },
        ) {
            Column {
                Text(
                    text = stringResource(
                        if (invalidUrl) R.string.settings_dns_custom_invalid
                        else R.string.settings_dns_custom_summary
                    ),
                    color = if (invalidUrl) COUITheme.colorScheme.error
                    else COUITheme.colorScheme.onSurfaceVariantSummary,
                    style = COUITheme.textStyles.body2,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                TextField(
                    value = customUrl,
                    onValueChange = {
                        customUrl = it
                        invalidUrl = false
                    },
                    label = stringResource(R.string.settings_dns_custom_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                DialogButtonBar(
                    negative = DialogButtonBarAction(
                        text = stringResource(android.R.string.cancel),
                        onClick = { showCustomDialog = false }
                    ),
                    positive = DialogButtonBarAction(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            if (NetworkDns.setCustomUrl(customUrl)) {
                                selectedProvider = DnsProvider.CUSTOM
                                showCustomDialog = false
                            } else {
                                invalidUrl = true
                            }
                        }
                    )
                )
            }
        }
    }
}

@Composable
fun InstallationSettings() {
    val context = LocalContext.current
    var installNotificationEnabled by remember { mutableStateOf(Configs.installNotificationEnabled) }
    var installAllUsers by remember { mutableStateOf(Configs.installAllUsers) }
    var thirdPartyInstallerPackage by remember { mutableStateOf(Configs.thirdPartyInstallerPackage) }
    var showInstallerDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Configs.installNotificationEnabled = isGranted
        installNotificationEnabled = isGranted
    }

    SwitchPreference(
        title = stringResource(R.string.settings_install_notification),
        summary = stringResource(R.string.settings_install_notification_summary),
        checked = installNotificationEnabled,
        onCheckedChange = { enable ->
            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !InstallNotificationHelper.hasNotificationPermission(context)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Configs.installNotificationEnabled = enable
                installNotificationEnabled = enable
            }
        },
        startAction = { SettingsStartIcon(Icons.Outlined.ArrowUpward) },
    )

    SwitchPreference(
        title = stringResource(R.string.settings_install_all_users),
        summary = stringResource(R.string.settings_install_all_users_summary),
        checked = installAllUsers,
        onCheckedChange = {
            Configs.installAllUsers = it
            installAllUsers = it
        },
        startAction = { SettingsStartIcon(Icons.Outlined.Person) },
    )

    val currentInstallerSummary = remember(thirdPartyInstallerPackage) {
        if (thirdPartyInstallerPackage.isBlank()) {
            context.getString(R.string.settings_third_party_installer_system_default)
        } else {
            runCatching {
                val appInfo = context.packageManager.getApplicationInfo(thirdPartyInstallerPackage, 0)
                "${context.packageManager.getApplicationLabel(appInfo)} ($thirdPartyInstallerPackage)"
            }.getOrDefault(thirdPartyInstallerPackage)
        }
    }

    ArrowPreference(
        title = stringResource(R.string.settings_third_party_installer),
        summary = currentInstallerSummary,
        startAction = { SettingsStartIcon(Icons.Outlined.Android) },
        onClick = { showInstallerDialog = true },
    )

    if (showInstallerDialog) {
        val discoveredInstallers = remember {
            ThirdPartyPackageInstaller.getDiscoveredInstallers(context)
        }
        var customPkgText by rememberSaveable {
            mutableStateOf(
                if (thirdPartyInstallerPackage.isNotBlank() && discoveredInstallers.none { it.packageName == thirdPartyInstallerPackage }) {
                    thirdPartyInstallerPackage
                } else {
                    ""
                }
            )
        }
        var isCustomError by rememberSaveable { mutableStateOf(false) }

        OverlayDialog(
            title = stringResource(R.string.settings_third_party_installer_dialog_title),
            show = true,
            onDismissRequest = { showInstallerDialog = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // System default option
                val isDefaultSelected = thirdPartyInstallerPackage.isBlank()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            Configs.thirdPartyInstallerPackage = ""
                            thirdPartyInstallerPackage = ""
                            showInstallerDialog = false
                        }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_third_party_installer_system_default),
                            style = COUITheme.textStyles.body1,
                        )
                    }
                    if (isDefaultSelected) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Selected",
                            tint = COUITheme.colorScheme.primary,
                        )
                    }
                }

                // Discovered third-party installers
                discoveredInstallers.forEach { installer ->
                    val isSelected = thirdPartyInstallerPackage == installer.packageName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                Configs.thirdPartyInstallerPackage = installer.packageName
                                thirdPartyInstallerPackage = installer.packageName
                                showInstallerDialog = false
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = installer.label,
                                style = COUITheme.textStyles.body1,
                            )
                            Text(
                                text = installer.packageName,
                                style = COUITheme.textStyles.footnote1,
                                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = "Selected",
                                tint = COUITheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Custom package input
                Text(
                    text = stringResource(R.string.settings_third_party_installer_custom),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                TextField(
                    value = customPkgText,
                    onValueChange = {
                        customPkgText = it.trim()
                        isCustomError = false
                    },
                    label = stringResource(R.string.settings_third_party_installer_custom_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isCustomError) {
                    Text(
                        text = stringResource(R.string.settings_third_party_installer_invalid_pkg),
                        color = COUITheme.colorScheme.error,
                        style = COUITheme.textStyles.footnote1,
                        modifier = Modifier.padding(top = 4.dp, start = 8.dp),
                    )
                }

                Spacer(Modifier.height(16.dp))

                DialogButtonBar(
                    negative = DialogButtonBarAction(
                        text = stringResource(android.R.string.cancel),
                        onClick = { showInstallerDialog = false },
                    ),
                    positive = DialogButtonBarAction(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            if (customPkgText.isBlank()) {
                                Configs.thirdPartyInstallerPackage = ""
                                thirdPartyInstallerPackage = ""
                                showInstallerDialog = false
                            } else if (ThirdPartyPackageInstaller.isInstallerValid(context, customPkgText)) {
                                Configs.thirdPartyInstallerPackage = customPkgText
                                thirdPartyInstallerPackage = customPkgText
                                showInstallerDialog = false
                            } else {
                                isCustomError = true
                            }
                        },
                    ),
                )
            }
        }
    }
}

@Composable
fun AppearanceSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val supportsFloatingGlassBottomBarBlur = ThemeConfig.isFloatingGlassBottomBarBlurSupported()
    val themeState by ThemeConfig.getThemeFlow(context).collectAsState(
        initial = ThemeSettings(
            backgroundImageUri = "",
            useMonet = false,
            customColor = DEFAULT_CUSTOM_COLOR,
            themeMode = ThemeMode.SYSTEM,
            useFloatingGlassBottomBar = false,
            useFloatingGlassBottomBarBlur = supportsFloatingGlassBottomBarBlur,
            cardBackgroundAlphaPercent = DEFAULT_CARD_BACKGROUND_ALPHA_PERCENT,
        )
    )
    val bgImageUri = themeState.backgroundImageUri
    val useMonet = themeState.useMonet
    val customColor = themeState.customColor
    val useFloatingGlassBottomBar = themeState.useFloatingGlassBottomBar
    val useFloatingGlassBottomBarBlur = themeState.useFloatingGlassBottomBarBlur
    val cardBackgroundAlphaPercent = themeState.cardBackgroundAlphaPercent
    var cardBackgroundAlphaSlider by remember(cardBackgroundAlphaPercent) {
        mutableFloatStateOf(cardBackgroundAlphaPercent.toFloat())
    }
    val scrollState = rememberScrollState()
    val snackbarHost = LocalSnackbarHost.current
    val unknownErrorText = stringResource(R.string.error_unknown)
    val themeModeItems = listOf(
        stringResource(R.string.settings_theme_mode_system),
        stringResource(R.string.settings_theme_mode_light),
        stringResource(R.string.settings_theme_mode_dark)
    )
    val themeModeIndex = when (themeState.themeMode) {
        ThemeMode.SYSTEM -> 0
        ThemeMode.LIGHT -> 1
        ThemeMode.DARK -> 2
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                BackgroundImageStorage.persistFromUri(context, uri)
            }.onSuccess { storedPath ->
                context.dataStore.edit { prefs -> prefs[ThemeConfig.BG_IMAGE_URI] = storedPath }
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to persist background image", throwable)
                snackbarHost.showSnackbar(unknownErrorText)
            }
        }
    }

    OverlayDropdownPreference(
        title = stringResource(R.string.settings_theme_mode),
        items = themeModeItems,
        selectedIndex = themeModeIndex,
        startAction = {
            SettingsStartIcon(Icons.Outlined.SettingsBrightness)
        },
        onSelectedIndexChange = { index ->
            val mode = when (index) {
                1 -> ThemeMode.LIGHT
                2 -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            scope.launch {
                context.dataStore.edit { it[ThemeConfig.THEME_MODE] = mode.value }
            }
        }
    )

    SwitchPreference(
        title = stringResource(R.string.settings_monet_dynamic_color),
        summary = stringResource(R.string.settings_monet_dynamic_color_summary),
        checked = useMonet,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Palette)
        },
        onCheckedChange = { isChecked ->
            scope.launch { context.dataStore.edit { it[ThemeConfig.USE_MONET] = isChecked } }
        }
    )

    SwitchPreference(
        title = stringResource(R.string.settings_floating_glass_bottom_bar),
        summary = stringResource(R.string.settings_floating_glass_bottom_bar_summary),
        checked = useFloatingGlassBottomBar,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Dashboard)
        },
        onCheckedChange = { isChecked ->
            scope.launch { context.dataStore.edit { it[ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR] = isChecked } }
        }
    )

    AnimatedVisibility(visible = useFloatingGlassBottomBar) {
        SwitchPreference(
            title = stringResource(R.string.settings_floating_glass_bottom_bar_blur),
            summary = stringResource(R.string.settings_floating_glass_bottom_bar_blur_summary),
            checked = useFloatingGlassBottomBarBlur,
            startAction = {
                SettingsStartIcon(Icons.Outlined.BlurCircular)
            },
            onCheckedChange = { isChecked ->
                scope.launch { context.dataStore.edit { it[ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR_BLUR] = isChecked } }
            }
        )
    }

    BasicComponent(
        modifier = Modifier,
        title = stringResource(R.string.settings_custom_background_image),
        startAction = {
            SettingsStartIcon(Icons.Outlined.Image)
        },
        endActions = {
            if (bgImageUri.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            scope.launch {
                                BackgroundImageStorage.clear(context)
                                context.dataStore.edit { it[ThemeConfig.BG_IMAGE_URI] = "" }
                            }
                        }
                        .background(COUITheme.colorScheme.error.copy(alpha = 0.1f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_clear),
                        color = COUITheme.colorScheme.error,
                        style = COUITheme.textStyles.body2
                    )
                }
            }
        },
        onClick = { imagePickerLauncher.launch(arrayOf("image/*")) }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsStartIcon(Icons.Outlined.Palette)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_card_background_alpha),
                    style = COUITheme.textStyles.body1,
                    color = COUITheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_card_background_alpha_summary),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Text(
                text = "${cardBackgroundAlphaSlider.roundToInt()}%",
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary
            )
        }
        Slider(
            value = cardBackgroundAlphaSlider,
            onValueChange = { value ->
                cardBackgroundAlphaSlider = value.roundToInt()
                    .coerceIn(CARD_BACKGROUND_ALPHA_MIN, CARD_BACKGROUND_ALPHA_MAX)
                    .toFloat()
            },
            valueRange = CARD_BACKGROUND_ALPHA_MIN.toFloat()..CARD_BACKGROUND_ALPHA_MAX.toFloat(),
            steps = CARD_BACKGROUND_ALPHA_MAX - CARD_BACKGROUND_ALPHA_MIN - 1,
            onValueChangeFinished = {
                val percent = cardBackgroundAlphaSlider.roundToInt()
                    .coerceIn(CARD_BACKGROUND_ALPHA_MIN, CARD_BACKGROUND_ALPHA_MAX)
                scope.launch {
                    context.dataStore.edit { it[ThemeConfig.CARD_BACKGROUND_ALPHA_PERCENT] = percent }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
        )
    }

    AnimatedVisibility(visible = !useMonet) {
        Column {
            Text(
                text = stringResource(R.string.settings_builtin_theme_color),
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val colorPalettes = listOf(
                    DEFAULT_CUSTOM_COLOR to stringResource(R.string.settings_color_cherry_blossom),
                    0xFF007AFF to stringResource(R.string.settings_color_default_blue),
                    0xFF34C759 to stringResource(R.string.settings_color_fresh_green),
                    0xFFAF52DE to stringResource(R.string.settings_color_elegant_purple),
                    0xFFFF9500 to stringResource(R.string.settings_color_vibrant_orange),
                    0xFF00BCD4 to stringResource(R.string.settings_color_cyan),
                    0xFF81C784 to stringResource(R.string.settings_color_mint_green),
                    0xFFF06292 to stringResource(R.string.settings_color_pink),
                    0xFFD81B60 to stringResource(R.string.settings_color_deep_pink),
                    0xFF64B5F6 to stringResource(R.string.settings_color_ice_blue),
                    0xFFE91E63 to stringResource(R.string.settings_color_rose)
                )

                colorPalettes.forEach { (colorHex, colorName) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.width(74.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Color(colorHex.toInt()))
                                .clickable {
                                    scope.launch { context.dataStore.edit { it[ThemeConfig.CUSTOM_COLOR] = colorHex.toInt() } }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (customColor == colorHex.toInt()) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Text(
                            text = colorName,
                            style = COUITheme.textStyles.footnote1,
                            color = COUITheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsStartIcon(imageVector: ImageVector) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = COUITheme.colorScheme.onBackground
        )
    }
}

private val LANGUAGE_ENTRIES = listOf(
    "" to "settings_language_system",
    "en" to "English",
    "zh-CN" to "中文 (简体)",
    "zh-MO" to "中文 (喵喵)",
    "zh-TW" to "中文 (繁體)",
    "zh-HK" to "中文 (香港)",
    "ja" to "日本語",
    "ko" to "한국어",
    "fr" to "Français",
    "de" to "Deutsch",
    "es" to "Español",
    "it" to "Italiano",
    "pt" to "Português",
    "pt-BR" to "Português (Brasil)",
    "ru" to "Русский",
    "ar" to "العربية",
    "tr" to "Türkçe",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "uk" to "Українська",
    "vi" to "Tiếng Việt",
    "th" to "ภาษาไทย",
    "hi" to "हिन्दी",
    "af" to "Afrikaans",
    "bg" to "Български",
    "bn" to "বাংলা",
    "ca" to "Català",
    "cs" to "Čeština",
    "da" to "Dansk",
    "el" to "Ελληνικά",
    "et" to "Eesti",
    "fa" to "فارسی",
    "fi" to "Suomi",
    "hr" to "Hrvatski",
    "hu" to "Magyar",
    "in" to "Bahasa Indonesia",
    "iw" to "עברית",
    "ku" to "Kurdî",
    "lt" to "Lietuvių",
    "no" to "Norsk",
    "ro" to "Română",
    "si" to "සිංහල",
    "sk" to "Slovenčina",
    "sv" to "Svenska",
    "ur" to "اردو",
)

@Composable
fun LanguagePreference() {
    val context = LocalContext.current
    val systemLabel = stringResource(R.string.settings_language_system)
    val languageLabels = remember(systemLabel) {
        LANGUAGE_ENTRIES.map { (_, label) -> if (label == "settings_language_system") systemLabel else label }
    }
    var selectedIndex by remember {
        mutableStateOf(
            LANGUAGE_ENTRIES.indexOfFirst {
                LSPApplication.normalizeLanguageTag(it.first) ==
                    LSPApplication.normalizeLanguageTag(Configs.language)
            }.takeIf { it >= 0 } ?: 0
        )
    }
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_language),
        items = languageLabels,
        selectedIndex = selectedIndex,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Language)
        },
        onSelectedIndexChange = { index ->
            selectedIndex = index
            val tag = LSPApplication.normalizeLanguageTag(LANGUAGE_ENTRIES[index].first)
            Configs.language = tag
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            (context as? Activity)?.finish()
        }
    )
}

@Composable
private fun KeyStore() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showDialog = remember { mutableStateOf(false) }
    val currentPreset = Configs.keyStorePreset

    val keyStoreItems = listOf(
        "NPatch",
        "FPA",
        stringResource(R.string.settings_keystore_custom)
    )
    var selectedIndex by remember { mutableStateOf(keyStorePresetIndex(currentPreset)) }
    LaunchedEffect(currentPreset) {
        selectedIndex = keyStorePresetIndex(currentPreset)
    }

    OverlayDropdownPreference(
        title = stringResource(R.string.settings_keystore),
        items = keyStoreItems,
        selectedIndex = selectedIndex,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Key)
        },
        onSelectedIndexChange = { index ->
            selectedIndex = index
            if (index == 0) {
                scope.launch { MyKeyStore.reset() }
            } else if (index == 1) {
                scope.launch { MyKeyStore.setBuiltinFpa() }
            } else {
                showDialog.value = true
            }
        }
    )

    if (showDialog.value) {
        var wrongKeystore by rememberSaveable { mutableStateOf(false) }
        var wrongPassword by rememberSaveable { mutableStateOf(false) }
        var wrongAliasName by rememberSaveable { mutableStateOf(false) }
        var wrongAliasPassword by rememberSaveable { mutableStateOf(false) }

        var path by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var alias by rememberSaveable { mutableStateOf("") }
        var aliasPassword by rememberSaveable { mutableStateOf("") }

        val dismissDialog = {
            showDialog.value = false
            selectedIndex = keyStorePresetIndex(Configs.keyStorePreset)
        }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            context.contentResolver.openInputStream(uri).use { input ->
                MyKeyStore.tmpFile.outputStream().use { output ->
                    input?.copyTo(output)
                }
            }
            path = uri.path ?: ""
        }

        val interactionSource = remember { MutableInteractionSource() }
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) {
                    launcher.launch("*/*")
                }
            }
        }

        OverlayDialog(
            title = stringResource(R.string.settings_keystore_dialog_title),
            show = showDialog.value,
            onDismissRequest = {
                dismissDialog()
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Error Message Handling
                val wrongText = when {
                    wrongAliasPassword -> stringResource(R.string.settings_keystore_wrong_alias_password)
                    wrongAliasName -> stringResource(R.string.settings_keystore_wrong_alias)
                    wrongPassword -> stringResource(R.string.settings_keystore_wrong_password)
                    wrongKeystore -> stringResource(R.string.settings_keystore_wrong_keystore)
                    else -> null
                }

                Text(
                    modifier = Modifier.padding(bottom = 8.dp),
                    text = wrongText ?: stringResource(R.string.settings_keystore_desc),
                    color = if (wrongText != null) COUITheme.colorScheme.error else COUITheme.colorScheme.onSurfaceVariantSummary,
                    style = COUITheme.textStyles.body2,
                    textAlign = TextAlign.Center
                )

                TextField(
                    value = path,
                    onValueChange = {},
                    label = stringResource(R.string.settings_keystore_file),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    interactionSource = interactionSource
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.settings_keystore_password),
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = stringResource(R.string.settings_keystore_alias),
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = aliasPassword,
                    onValueChange = { aliasPassword = it },
                    label = stringResource(R.string.settings_keystore_alias_password),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                DialogButtonBar(
                    negative = DialogButtonBarAction(
                        text = stringResource(android.R.string.cancel),
                        onClick = dismissDialog
                    ),
                    positive = DialogButtonBarAction(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            wrongKeystore = false
                            wrongPassword = false
                            wrongAliasName = false
                            wrongAliasPassword = false

                            if (path.isEmpty()) {
                                wrongKeystore = true
                                return@DialogButtonBarAction
                            }
                            val keyStore = KeyStore.getInstance("BKS")
                            try {
                                MyKeyStore.tmpFile.inputStream().use { input ->
                                    keyStore.load(input, password.toCharArray())
                                }
                            } catch (e: IOException) {
                                wrongKeystore = true
                                if (e.message == "KeyStore integrity check failed.") {
                                    wrongPassword = true
                                }
                                return@DialogButtonBarAction
                            }
                            if (!keyStore.containsAlias(alias)) {
                                wrongAliasName = true
                                return@DialogButtonBarAction
                            }
                            try {
                                keyStore.getKey(alias, aliasPassword.toCharArray())
                            } catch (e: GeneralSecurityException) {
                                wrongAliasPassword = true
                                return@DialogButtonBarAction
                            }

                            scope.launch { MyKeyStore.setCustom(password, alias, aliasPassword) }
                            showDialog.value = false
                        }
                    )
                )
            }
        }
    }
}

private fun keyStorePresetIndex(preset: KeystorePreset): Int {
    return when (preset) {
        KeystorePreset.NPATCH -> 0
        KeystorePreset.FPA -> 1
        KeystorePreset.CUSTOM -> 2
    }
}

@Composable
private fun DetailPatchLogs() {
    SwitchPreference(
        title = stringResource(R.string.settings_detail_patch_logs),
        startAction = {
            SettingsStartIcon(Icons.Outlined.BugReport)
        },
        checked = Configs.detailPatchLogs,
        onCheckedChange = { Configs.detailPatchLogs = it }
    )
}

@Composable
private fun OutputFullLog() {
    SwitchPreference(
        title = stringResource(R.string.settings_output_full_log),
        summary = stringResource(R.string.settings_output_full_log_summary),
        checked = Configs.outputFullLog,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Description)
        },
        onCheckedChange = {
            Configs.outputFullLog = it
            ManagerLogger.setEnabled(it)
        }
    )
}

@Composable
private fun WelcomeGuide() {
    val navigator = LocalNavigator.current
    ArrowPreference(
        title = stringResource(R.string.settings_view_welcome),
        summary = stringResource(R.string.settings_view_welcome_summary),
        startAction = {
            SettingsStartIcon(Icons.Outlined.Info)
        },
        onClick = { navigator.push(Route.Welcome(reviewMode = true)) }
    )
}

@Composable
fun StorageDirectory() {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val errorText = stringResource(R.string.patch_select_dir_error)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        try {
            if (it.resultCode == Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
            val uri = it.data?.data ?: throw IOException("No data")
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            Configs.storageDirectory = uri.toString()
            Log.i(TAG, "Storage directory: ${uri.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Error when requesting saving directory", e)
            scope.launch { snackbarHost.showSnackbar(errorText) }
        }
    }
    ArrowPreference(
        title = stringResource(R.string.settings_storage_directory),
        summary = Configs.storageDirectory ?: "no path set",
        startAction = {
            SettingsStartIcon(Icons.Outlined.Folder)
        },
        onClick = { launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
    )
}

@Composable
fun ClearManagerCache() {
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val clearText = stringResource(R.string.settings_manager_cache)
    val summaryText = stringResource(R.string.settings_manager_cache_summary)
    val dialogText = stringResource(R.string.settings_manager_cache_dialog_text)
    val successText = stringResource(R.string.settings_manager_cache_success)
    val failedText = stringResource(R.string.settings_manager_cache_failed)
    val showDialog = remember { mutableStateOf(false) }

    ArrowPreference(
        title = clearText,
        summary = summaryText,
        startAction = {
            SettingsStartIcon(Icons.Outlined.DeleteSweep)
        },
        onClick = { showDialog.value = true }
    )

    if (showDialog.value) {
        OverlayDialog(
            title = clearText,
            summary = dialogText,
            show = showDialog.value,
            onDismissRequest = { showDialog.value = false },
        ) {
            DialogButtonBar(
                negative = DialogButtonBarAction(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showDialog.value = false }
                ),
                positive = DialogButtonBarAction(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        showDialog.value = false
                        scope.launch {
                            runCatching {
                                ManagerCacheCleaner.clear()
                            }.onSuccess {
                                snackbarHost.showSnackbar(successText)
                            }.onFailure {
                                Log.e(TAG, "Failed to clear manager cache", it)
                                snackbarHost.showSnackbar(failedText)
                            }
                        }
                    }
                )
            )
        }
    }
}
