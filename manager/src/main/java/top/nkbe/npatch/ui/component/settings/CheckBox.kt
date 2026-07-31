package top.nkbe.npatch.ui.component.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.state.ToggleableState
import io.github.suqi8.coui.kmp.basic.Checkbox

@Composable
fun SettingsCheckBox(
    modifier: Modifier = Modifier,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    title: String,
    desc: String? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    @Suppress("DEPRECATION")
    SettingsSlot(modifier, enabled, icon, title, desc, extraContent) {
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = null
        )
    }
}

@Preview
@Composable
private fun SettingsCheckBoxPreview() {
    var checked1 by remember { mutableStateOf(false) }
    var checked2 by remember { mutableStateOf(false) }
    Column {
        SettingsCheckBox(
            modifier = Modifier.clickable { checked1 = !checked1 },
            checked = checked1,
            title = "Title",
            desc = "Description"
        )
        SettingsCheckBox(
            modifier = Modifier.clickable { checked2 = !checked2 },
            checked = checked2,
            icon = Icons.Outlined.Api,
            title = "Title",
            desc = "Description"
        )
    }
}
