package top.nkbe.npatch.ui.component.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.github.suqi8.coui.kmp.basic.Switch

@Composable
fun SettingsSwitch(
    modifier: Modifier = Modifier,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    title: String,
    desc: String? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    SettingsSlot(
        modifier = modifier.semantics(mergeDescendants = true) {
            role = Role.Switch
            this.toggleableState = ToggleableState(checked)
        },
        enabled = enabled,
        icon = icon,
        title = title,
        desc = desc,
        extraContent = extraContent
    ) {
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Preview
@Composable
private fun SettingsCheckBoxPreview() {
    var checked1 by remember { mutableStateOf(false) }
    var checked2 by remember { mutableStateOf(false) }
    Column {
        SettingsSwitch(
            modifier = Modifier.clickable { checked1 = !checked1 },
            checked = checked1,
            title = "Title",
            desc = "Description"
        )
        SettingsSwitch(
            modifier = Modifier.clickable { checked2 = !checked2 },
            checked = checked2,
            icon = Icons.Outlined.Api,
            title = "Title",
            desc = "Description"
        )
    }
}
