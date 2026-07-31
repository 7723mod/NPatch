package top.nkbe.npatch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Ok
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun AccessibleMenuItem(
    text: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = text,
                style = COUITheme.textStyles.body1,
                color = COUITheme.colorScheme.onSurface
            )
            if (!summary.isNullOrEmpty()) {
                Text(
                    text = summary,
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }

        if (selected) {
            Icon(
                imageVector = COUIIcons.Regular.Ok,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = COUITheme.colorScheme.primary
            )
        }
    }
}
