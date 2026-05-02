package org.lsposed.npatch.ui.page.newpatch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.lsposed.npatch.R
import org.lsposed.npatch.ui.component.SelectionColumn
import org.lsposed.npatch.ui.component.SelectionColumnScope.SelectionItem
import org.lsposed.npatch.ui.component.settings.SettingsEditor
import org.lsposed.npatch.ui.util.backgroundAwareCardColors
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel
import org.lsposed.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ConfiguringTopBar(scrollBehavior: ScrollBehavior, onBackClick: () -> Unit) {
    TopAppBar(
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
    FloatingActionButton(
        onClick = { viewModel.dispatch(ViewAction.SubmitPatch) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Outlined.AutoFixHigh, null)
            Text(stringResource(R.string.patch_start))
        }
    }
}

@Composable
fun sigBypassLvStr(level: Int) = when (level) {
    0 -> stringResource(R.string.patch_sigbypasslv0)
    1 -> stringResource(R.string.patch_sigbypasslv1)
    2 -> stringResource(R.string.patch_sigbypasslv2)
    3 -> stringResource(R.string.patch_sigbypasslv3)
    4 -> stringResource(R.string.patch_sigbypasslv4)
    else -> throw IllegalArgumentException("Invalid sigBypassLv: $level")
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
                Text(text = viewModel.patchApp.label, style = MiuixTheme.textStyles.headline1)
                Text(
                    text = viewModel.patchApp.app.packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                    onClick = { viewModel.useManager = true },
                    icon = Icons.Outlined.Api,
                    title = stringResource(R.string.patch_local),
                    desc = stringResource(R.string.patch_local_desc)
                )
                SelectionItem(
                    modifier = Modifier.clip(itemShape),
                    selected = !viewModel.useManager,
                    onClick = { viewModel.useManager = false },
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
                            color = MiuixTheme.colorScheme.primary,
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                )
            }
        }

        // ── 進階配置 ──
        SmallTitle(text = stringResource(R.string.patch_advanced))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
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
                SuperSwitch(
                    title = stringResource(R.string.patch_debuggable),
                    startAction = { Icon(Icons.Outlined.BugReport, null) },
                    checked = viewModel.debuggable,
                    onCheckedChange = { viewModel.debuggable = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_override_version_code),
                    summary = stringResource(R.string.patch_override_version_code_desc),
                    startAction = { Icon(Icons.Outlined.Layers, null) },
                    checked = viewModel.overrideVersionCode,
                    onCheckedChange = { viewModel.overrideVersionCode = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_inject_dex),
                    summary = stringResource(R.string.patch_inject_dex_desc),
                    startAction = { Icon(Icons.Outlined.Code, null) },
                    checked = viewModel.injectDex,
                    onCheckedChange = { viewModel.injectDex = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_inject_mt_provider),
                    summary = stringResource(R.string.patch_inject_mt_provider_desc),
                    startAction = { Icon(Icons.Outlined.AddCard, null) },
                    checked = viewModel.injectProvider,
                    onCheckedChange = { viewModel.injectProvider = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_use_microg),
                    summary = stringResource(R.string.patch_use_microg_desc),
                    startAction = { Icon(Icons.Outlined.CloudSync, null) },
                    checked = viewModel.useMicroG,
                    onCheckedChange = { viewModel.useMicroG = it }
                )
                SuperSwitch(
                    title = stringResource(R.string.patch_output_log_to_media),
                    summary = stringResource(R.string.patch_output_log_to_media_desc),
                    startAction = { Icon(Icons.Outlined.Output, null) },
                    checked = viewModel.outputLog,
                    onCheckedChange = { viewModel.outputLog = it }
                )
                SuperDropdown(
                    title = stringResource(R.string.patch_sigbypass),
                    items = (0..4).map { sigBypassLvStr(it) },
                    selectedIndex = viewModel.sigBypassLevel,
                    onSelectedIndexChange = { viewModel.sigBypassLevel = it }
                )
            }
        }
    }
}
