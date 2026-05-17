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
import androidx.compose.material.icons.outlined.Ballot
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.launch
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.MyKeyStore
import top.nkbe.npatch.config.ThemeConfig
import top.nkbe.npatch.config.ThemeMode
import top.nkbe.npatch.config.ThemeSettings
import top.nkbe.npatch.config.dataStore
import top.nkbe.npatch.ui.activity.MainActivity
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.BackgroundImageStorage
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

private const val TAG = "SettingsScreen"

@Composable
fun SettingsScreen() {
    val scrollBehavior = MiuixScrollBehavior()
    NPatchScaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.screen_settings),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
        ) {
            SmallTitle(text = stringResource(R.string.settings_appearance_theme))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = backgroundAwareCardColors(),
            ) {
                AppearanceSettings()
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
                WelcomeGuide()
                StorageDirectory()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun AppearanceSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeState by ThemeConfig.getThemeFlow(context).collectAsState(
        initial = ThemeSettings(
            backgroundImageUri = "",
            useMonet = false,
            customColor = 0xFF007AFF.toInt(),
            themeMode = ThemeMode.SYSTEM,
            useFloatingGlassBottomBar = false,
            useFloatingGlassBottomBarBlur = true,
        )
    )
    val bgImageUri = themeState.backgroundImageUri
    val useMonet = themeState.useMonet
    val customColor = themeState.customColor
    val useFloatingGlassBottomBar = themeState.useFloatingGlassBottomBar
    val useFloatingGlassBottomBarBlur = themeState.useFloatingGlassBottomBarBlur
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
            Icon(
                Icons.Outlined.SettingsBrightness,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
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
        onCheckedChange = { isChecked ->
            scope.launch { context.dataStore.edit { it[ThemeConfig.USE_MONET] = isChecked } }
        },
        startAction = {
            Icon(
                Icons.Outlined.Palette,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
        }
    )

    SwitchPreference(
        title = stringResource(R.string.settings_floating_glass_bottom_bar),
        summary = stringResource(R.string.settings_floating_glass_bottom_bar_summary),
        checked = useFloatingGlassBottomBar,
        onCheckedChange = { isChecked ->
            scope.launch { context.dataStore.edit { it[ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR] = isChecked } }
        },
        startAction = {
            Icon(
                Icons.Outlined.Palette,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
        }
    )

    AnimatedVisibility(visible = useFloatingGlassBottomBar) {
        SwitchPreference(
            title = stringResource(R.string.settings_floating_glass_bottom_bar_blur),
            summary = stringResource(R.string.settings_floating_glass_bottom_bar_blur_summary),
            checked = useFloatingGlassBottomBarBlur,
            onCheckedChange = { isChecked ->
                scope.launch { context.dataStore.edit { it[ThemeConfig.USE_FLOATING_GLASS_BOTTOM_BAR_BLUR] = isChecked } }
            },
            startAction = {
                Icon(
                    Icons.Outlined.Palette,
                    modifier = Modifier.padding(end = 6.dp),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onBackground
                )
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { imagePickerLauncher.launch(arrayOf("image/*")) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Image,
            modifier = Modifier.padding(end = 16.dp),
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.settings_custom_background_image),
            style = MiuixTheme.textStyles.title3,
            modifier = Modifier.weight(1f)
        )
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
                    .background(MiuixTheme.colorScheme.error.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_clear),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }

    AnimatedVisibility(visible = !useMonet) {
        Column {
            Text(
                text = stringResource(R.string.settings_builtin_theme_color),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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

private val LANGUAGE_ENTRIES = listOf(
    "" to "settings_language_system",
    "en" to "English",
    "zh-CN" to "中文 (简体)",
    "zh-x-nya" to "中文 (喵喵)",
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
            LANGUAGE_ENTRIES.indexOfFirst { it.first == Configs.language }.takeIf { it >= 0 } ?: 0
        )
    }
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_language),
        items = languageLabels,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            selectedIndex = index
            val tag = LANGUAGE_ENTRIES[index].first
            Configs.language = tag
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            (context as? Activity)?.finish()
        },
        startAction = {
            Icon(
                Icons.Outlined.Language,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
        }
    )
}

@Composable
private fun KeyStore() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showDialog = remember { mutableStateOf(false) }

    val keyStoreItems = listOf(
        stringResource(R.string.settings_keystore_default),
        stringResource(R.string.settings_keystore_custom)
    )
    var selectedIndex by remember { mutableStateOf(if (MyKeyStore.useDefault) 0 else 1) }

    OverlayDropdownPreference(
        title = stringResource(R.string.settings_keystore),
        items = keyStoreItems,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            selectedIndex = index
            if (index == 0) {
                scope.launch { MyKeyStore.reset() }
            } else {
                showDialog.value = true
            }
        },
        startAction = {
            Icon(
                Icons.Outlined.Ballot,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
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
                showDialog.value = false
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
                    color = if (wrongText != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
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

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = { showDialog.value = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            wrongKeystore = false
                            wrongPassword = false
                            wrongAliasName = false
                            wrongAliasPassword = false

                            if (path.isEmpty()) {
                                wrongKeystore = true
                                return@TextButton
                            }
                            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                            try {
                                MyKeyStore.tmpFile.inputStream().use { input ->
                                    keyStore.load(input, password.toCharArray())
                                }
                            } catch (e: IOException) {
                                wrongKeystore = true
                                if (e.message == "KeyStore integrity check failed.") {
                                    wrongPassword = true
                                }
                                return@TextButton
                            }
                            if (!keyStore.containsAlias(alias)) {
                                wrongAliasName = true
                                return@TextButton
                            }
                            try {
                                keyStore.getKey(alias, aliasPassword.toCharArray())
                            } catch (e: GeneralSecurityException) {
                                wrongAliasPassword = true
                                return@TextButton
                            }

                            scope.launch { MyKeyStore.setCustom(password, alias, aliasPassword) }
                            showDialog.value = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailPatchLogs() {
    SwitchPreference(
        title = stringResource(R.string.settings_detail_patch_logs),
        startAction = {
            Icon(
                Icons.Outlined.BugReport,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
        },
        checked = Configs.detailPatchLogs,
        onCheckedChange = { Configs.detailPatchLogs = it }
    )
}

@Composable
private fun WelcomeGuide() {
    val navigator = LocalNavigator.current
    ArrowPreference(
        title = stringResource(R.string.settings_view_welcome),
        summary = stringResource(R.string.settings_view_welcome_summary),
        startAction = {
            Icon(
                Icons.Outlined.Info,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
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
            Icon(
                Icons.Outlined.Folder,
                modifier = Modifier.padding(end = 6.dp),
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onBackground
            )
        },
        onClick = { launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
    )
}
