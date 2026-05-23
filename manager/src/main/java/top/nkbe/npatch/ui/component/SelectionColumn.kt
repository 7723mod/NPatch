package top.nkbe.npatch.ui.component

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
            targetValue = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
            else Color.Transparent,
            label = "SelectionItemBg"
        ).value

        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .background(backgroundColor)
                .clickable { onClick() } 
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title3
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
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.67f)
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
