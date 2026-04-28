package org.lsposed.npatch.ui.page

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Ballot
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

import kotlinx.coroutines.launch
import org.lsposed.npatch.R
import org.lsposed.npatch.config.Configs
import org.lsposed.npatch.config.MyKeyStore
import org.lsposed.npatch.ui.activity.MainActivity

import org.lsposed.npatch.ui.util.LocalSnackbarHost
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.WindowBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import androidx.compose.ui.input.nestedscroll.nestedScroll

private const val TAG = "SettingsScreen"

@Composable
fun SettingsScreen() {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = stringResource(R.string.screen_settings),
                scrollBehavior = scrollBehavior
            )
        },
        popupHost = {}
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
            Card(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
            ) {
                Language()
                KeyStore()
                DetailPatchLogs()
                StorageDirectory()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val LANGUAGE_ENTRIES = listOf(
    "" to "settings_language_system",
    "en" to "English",
    "zh-CN" to "中文 (简体)",
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
private fun Language() {
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
    SuperDropdown(
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

    SuperDropdown(
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

        SuperDialog(
            title = stringResource(R.string.settings_keystore_dialog_title),
            show = showDialog,
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
    SuperSwitch(
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
private fun StorageDirectory() {
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
    SuperArrow(
        title = stringResource(R.string.settings_storage_directory),
        summary = Configs.storageDirectory ?: "undefined",
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
