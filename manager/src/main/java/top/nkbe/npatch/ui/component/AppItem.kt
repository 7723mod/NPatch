package top.nkbe.npatch.ui.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Surface
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun AppItem(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    packageName: String,
    summaryRow: (@Composable RowScope.() -> Unit)? = null,
    topRightContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    description: String = "",
    warningText: String? = null,
    isEnabled: Boolean = true,
    onClick: () -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    var descriptionExpanded by remember { mutableStateOf(false) }
    val colorScheme = MiuixTheme.colorScheme

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        colors = backgroundAwareCardColors(),
        insideMargin = PaddingValues(12.dp),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
        onLongPress = onLongPress
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .alpha(if (isEnabled) 1f else 0.4f),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (isEnabled) 1f else 0.45f)
                        .align(Alignment.CenterVertically)
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.basicMarquee(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight(600),
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = packageName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(500),
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        softWrap = false
                    )

                    if (summaryRow != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            content = summaryRow
                        )
                    }
                }

                if (trailingContent != null) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                        trailingContent()
                    }
                }

                if (topRightContent != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(modifier = Modifier.align(Alignment.Top)) {
                        topRightContent()
                    }
                }
            }

            // 底部描述与警告区域
            if (description.isNotEmpty() || warningText != null) {
                Spacer(modifier = Modifier.height(4.dp))

                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        fontSize = 13.sp,
                        fontWeight = FontWeight(500),
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { descriptionExpanded = !descriptionExpanded }
                    )
                }

                if (warningText != null) {
                    if (description.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = warningText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight(550),
                            color = colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
