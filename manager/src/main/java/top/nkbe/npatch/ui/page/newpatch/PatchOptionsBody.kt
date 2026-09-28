package top.nkbe.npatch.ui.page.newpatch

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.nkbe.npatch.R
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.component.SelectionColumn
import top.nkbe.npatch.ui.component.SelectionColumnScope.SelectionItem
import top.nkbe.npatch.ui.component.settings.SettingsEditor
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import io.github.suqi8.coui.kmp.basic.*
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun ConfiguringTopBar(scrollBehavior: ScrollBehavior, onBackClick: () -> Unit) {
    NPatchTopAppBar(
        title = stringResource(R.string.screen_new_patch),
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            }
        }
    )
}

@Composable
fun ConfiguringFab() {
    val viewModel = viewModel<NewPatchViewModel>()
    val patchStartText = stringResource(R.string.patch_start)
    FloatingActionButton(
        modifier = Modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = patchStartText
        },
        onClick = { viewModel.dispatch(ViewAction.SubmitPatch) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoFixHigh,
                contentDescription = null,
                tint = COUITheme.colorScheme.onPrimary
            )
            Text(
                text = patchStartText,
                color = COUITheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
fun sigBypassLvTitle(level: Int): String {
    return when (level) {
        0 -> stringResource(R.string.patch_sigbypasslv0)
        1 -> stringResource(R.string.patch_sigbypasslv1)
        2 -> stringResource(R.string.patch_sigbypasslv2)
        3 -> stringResource(R.string.patch_sigbypasslv3)
        4 -> stringResource(R.string.patch_sigbypasslv4)
        else -> error("Invalid sigBypassLv: $level")
    }
}

@Composable
fun sigBypassLvDesc(level: Int): String {
    return when (level) {
        0 -> stringResource(R.string.patch_sigbypasslv0_desc)
        1 -> stringResource(R.string.patch_sigbypasslv1_desc)
        2 -> stringResource(R.string.patch_sigbypasslv2_desc)
        3 -> stringResource(R.string.patch_sigbypasslv3_desc)
        4 -> stringResource(R.string.patch_sigbypasslv4_desc)
        else -> error("Invalid sigBypassLv: $level")
    }
}

@Composable
fun PatchOptionsBody(modifier: Modifier, onAddEmbed: () -> Unit) {
    val viewModel = viewModel<NewPatchViewModel>()
    val cardShape = RoundedCornerShape(24.dp)
    val itemShape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 84.dp)
    ) {
        SmallTitle(text = stringResource(R.string.patch_mode))

        // ── 應用資訊 ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
                .clip(cardShape),
            colors = backgroundAwareCardColors(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(text = viewModel.patchApp.label, style = COUITheme.textStyles.headline1)
                Text(
                    text = viewModel.patchApp.app.packageName,
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        // ── 修補模式選擇 ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .clip(cardShape),
            colors = backgroundAwareCardColors(),
        ) {
            SelectionColumn(Modifier.padding(8.dp)) {
                SelectionItem(
                    modifier = Modifier.clip(itemShape),
                    selected = viewModel.useManager,
                    onClick = { viewModel.setUseManager(true) },
                    icon = Icons.Outlined.Api,
                    title = stringResource(R.string.patch_local),
                    desc = stringResource(R.string.patch_local_desc)
                )
                SelectionItem(
                    modifier = Modifier.clip(itemShape),
                    selected = !viewModel.useManager,
                    onClick = { viewModel.setUseManager(false) },
                    icon = Icons.Outlined.WorkOutline,
                    title = stringResource(R.string.patch_integrated),
                    desc = stringResource(R.string.patch_integrated_desc),
                    extraContent = {
                        val embedText = if (viewModel.embeddedModules.isNotEmpty()) {
                            stringResource(R.string.patch_embed_modules) + " (${viewModel.embeddedModules.size})"
                        } else {
                            stringResource(R.string.patch_embed_modules)
                        }
                        Text(
                            text = embedText,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable(onClick = onAddEmbed),
                            color = COUITheme.colorScheme.primary,
                            style = COUITheme.textStyles.body2
                        )
                    }
                )
            }
        }

        // ── 獨立子進程檢測提示 ──
        if (viewModel.hasSubProcesses) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp)
                    .clip(cardShape),
                colors = backgroundAwareCardColors(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        tint = COUITheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.patch_subprocess_detected_hint, viewModel.subProcessCount),
                        style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }

        // ── 基本設定（常駐）──
        SmallTitle(text = stringResource(R.string.patch_advanced))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
                .clip(cardShape),
            colors = backgroundAwareCardColors(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                SettingsEditor(
                    Modifier.padding(horizontal = 12.dp),
                    stringResource(R.string.patch_new_package),
                    viewModel.newPackageName,
                    onValueChange = { viewModel.newPackageName = it },
                )

                val maxSigBypassLevel = if (viewModel.useManager) {
                    Constants.SIGBYPASS_SECCOMP
                } else {
                    Constants.SIGBYPASS_EXTREME
                }
                val sigBypassEntries = listOf(
                    DropdownEntry(
                        items = (Constants.SIGBYPASS_NONE..maxSigBypassLevel).map { level ->
                            DropdownItem(
                                text = sigBypassLvTitle(level),
                                summary = sigBypassLvDesc(level),
                                selected = viewModel.sigBypassLevel == level,
                                onClick = {
                                    viewModel.sigBypassLevel = level
                                    if (level == Constants.SIGBYPASS_NONE) {
                                        viewModel.hideLibs = false
                                    }
                                }
                            )
                        }
                    )
                )
                SwitchPreference(
                    title = stringResource(R.string.patch_output_log_to_media),
                    summary = stringResource(R.string.patch_output_log_to_media_desc),
                    startAction = { Icon(Icons.Outlined.Output, null) },
                    checked = viewModel.outputLog,
                    onCheckedChange = { viewModel.outputLog = it }
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.patch_sigbypass),
                    startAction = { Icon(Icons.Outlined.Security, null) },
                    entries = sigBypassEntries
                )
            }
        }

        // ── 更多選項（折疊收納）──
        var moreOptionsExpanded by rememberSaveable { mutableStateOf(false) }
        val chevronRotation by animateFloatAsState(
            targetValue = if (moreOptionsExpanded) 180f else 0f,
            label = "moreOptionsChevron"
        )
        val activeChips = buildList {
            if (viewModel.overrideLabel.isNotBlank()) {
                add(viewModel.overrideLabel to Icons.Outlined.Edit)
            }
            if (viewModel.extractNativeLibs) {
                add(stringResource(R.string.patch_extract_native_libs) to Icons.Outlined.Unarchive)
            }
            if (viewModel.debuggable) {
                add(stringResource(R.string.patch_debuggable) to Icons.Outlined.BugReport)
            }
            if (viewModel.overrideVersionCode) {
                add("vc: ${viewModel.overrideVersionCodeValue}" to Icons.Outlined.Layers)
            }
            if (viewModel.overrideTargetSdk) {
                add("sdk: ${viewModel.overrideTargetSdkValue}" to Icons.Outlined.Android)
            }
            if (viewModel.injectProvider) {
                add(stringResource(R.string.patch_inject_mt_provider) to Icons.Outlined.AddCard)
            }
            if (viewModel.injectDex) {
                add(stringResource(R.string.patch_inject_dex) to Icons.Outlined.AccountTree)
            }
            if (viewModel.useMicroG) {
                add(stringResource(R.string.patch_use_microg) to Icons.Outlined.CloudSync)
            }
            if (viewModel.usesCleartextTraffic) {
                add(stringResource(R.string.patch_cleartext_traffic) to Icons.Outlined.Http)
            }
            if (viewModel.hideLibs &&
                viewModel.sigBypassLevel > Constants.SIGBYPASS_NONE
            ) {
                add(stringResource(R.string.patch_hide_libs) to Icons.Outlined.VisibilityOff)
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .clip(cardShape)
                .animateContentSize(),
            colors = backgroundAwareCardColors(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(itemShape)
                        .clickable { moreOptionsExpanded = !moreOptionsExpanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = COUITheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.patch_more_options),
                            style = COUITheme.textStyles.headline2,
                        )
                        if (!moreOptionsExpanded && activeChips.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeChips.forEach { (label, icon) ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = COUITheme.colorScheme.primary.copy(alpha = 0.1f),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = icon,
                                                contentDescription = null,
                                                modifier = Modifier.size(12.dp),
                                                tint = COUITheme.colorScheme.primary
                                            )
                                            Text(
                                                text = label,
                                                style = COUITheme.textStyles.footnote1,
                                                color = COUITheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Icon(
                        imageVector = Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = COUITheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.rotate(chevronRotation)
                    )
                }

                if (moreOptionsExpanded) {
                    SettingsEditor(
                        Modifier.padding(horizontal = 12.dp),
                        stringResource(R.string.patch_override_label),
                        viewModel.overrideLabel,
                        onValueChange = { viewModel.overrideLabel = it },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.patch_extract_native_libs),
                        summary = stringResource(R.string.patch_extract_native_libs_desc),
                        startAction = { Icon(Icons.Outlined.Unarchive, null) },
                        checked = viewModel.extractNativeLibs,
                        onCheckedChange = { viewModel.extractNativeLibs = it }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.patch_debuggable),
                        startAction = { Icon(Icons.Outlined.BugReport, null) },
                        checked = viewModel.debuggable,
                        onCheckedChange = { viewModel.debuggable = it }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.patch_override_version_code),
                        summary = stringResource(R.string.patch_override_version_code_desc),
                        startAction = { Icon(Icons.Outlined.Layers, null) },
                        checked = viewModel.overrideVersionCode,
                        onCheckedChange = { viewModel.overrideVersionCode = it }
                    )
                    if (viewModel.overrideVersionCode) {
                        SettingsEditor(
                            Modifier.padding(horizontal = 12.dp),
                            stringResource(R.string.patch_custom_version_code),
                            viewModel.overrideVersionCodeValue,
                            onValueChange = { value ->
                                viewModel.overrideVersionCodeValue = value.filter { it in '0'..'9' }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    SwitchPreference(
                        title = stringResource(R.string.patch_override_target_sdk),
                        summary = stringResource(R.string.patch_override_target_sdk_desc),
                        startAction = { Icon(Icons.Outlined.Android, null) },
                        checked = viewModel.overrideTargetSdk,
                        onCheckedChange = { viewModel.overrideTargetSdk = it }
                    )
                    if (viewModel.overrideTargetSdk) {
                        SettingsEditor(
                            Modifier.padding(horizontal = 12.dp),
                            stringResource(R.string.patch_custom_target_sdk),
                            viewModel.overrideTargetSdkValue,
                            onValueChange = { value ->
                                viewModel.overrideTargetSdkValue = value.filter { it in '0'..'9' }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    SwitchPreference(
                        title = stringResource(R.string.patch_inject_mt_provider),
                        summary = stringResource(R.string.patch_inject_mt_provider_desc),
                        startAction = { Icon(Icons.Outlined.AddCard, null) },
                        checked = viewModel.injectProvider,
                        onCheckedChange = { viewModel.injectProvider = it }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.patch_inject_dex),
                        summary = stringResource(R.string.patch_inject_dex_desc),
                        startAction = { Icon(Icons.Outlined.AccountTree, null) },
                        checked = viewModel.injectDex,
                        onCheckedChange = { viewModel.injectDex = it }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.patch_use_microg),
                        summary = stringResource(R.string.patch_use_microg_desc),
                        startAction = { Icon(Icons.Outlined.CloudSync, null) },
                        checked = viewModel.useMicroG,
                        onCheckedChange = { viewModel.useMicroG = it }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.patch_cleartext_traffic),
                        summary = stringResource(R.string.patch_cleartext_traffic_desc),
                        startAction = { Icon(Icons.Outlined.Http, null) },
                        checked = viewModel.usesCleartextTraffic,
                        onCheckedChange = { viewModel.usesCleartextTraffic = it }
                    )
                    SwitchPreference(
                        title = stringResource(R.string.patch_hide_libs),
                        summary = stringResource(R.string.patch_hide_libs_desc),
                        startAction = { Icon(Icons.Outlined.VisibilityOff, null) },
                        checked = viewModel.hideLibs && viewModel.sigBypassLevel > Constants.SIGBYPASS_NONE,
                        onCheckedChange = {
                            viewModel.hideLibs = it && viewModel.sigBypassLevel > Constants.SIGBYPASS_NONE
                        }
                    )
                }
            }
        }
    }
}
