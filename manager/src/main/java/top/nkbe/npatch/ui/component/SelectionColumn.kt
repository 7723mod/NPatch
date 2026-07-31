package top.nkbe.npatch.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.nkbe.npatch.R
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import androidx.compose.ui.res.stringResource

object SelectionColumnScope {

    @Composable
    fun SelectionItem(
        modifier: Modifier = Modifier,
        selected: Boolean,
        onClick: () -> Unit,
        icon: ImageVector,
        title: String,
        desc: String? = null,
        extraContent: (@Composable ColumnScope.() -> Unit)? = null
    ) {
        val backgroundColor = animateColorAsState(
            targetValue = if (selected) COUITheme.colorScheme.primary.copy(alpha = 0.1f)
            else Color.Transparent,
            label = "SelectionItemBg"
        ).value
        val selectedLabel = stringResource(R.string.accessibility_selected)

        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .background(backgroundColor)
                .semantics { stateDescription = if (selected) selectedLabel else "" }
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.RadioButton
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (selected) COUITheme.colorScheme.primary else COUITheme.colorScheme.onSurface
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = COUITheme.textStyles.title3
                )
                if (desc != null || extraContent != null) {
                    AnimatedVisibility(
                        visible = selected,
                        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                    ) {
                        Column {
                            if (desc != null) {
                                Text(
                                    text = desc,
                                    modifier = Modifier.padding(top = 4.dp),
                                    style = COUITheme.textStyles.body2,
                                    color = COUITheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.67f)
                                )
                            }
                            extraContent?.invoke(this)
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun SelectionColumn(
    modifier: Modifier = Modifier,
    content: @Composable (SelectionColumnScope.() -> Unit)
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = { SelectionColumnScope.content() }
    )
}
