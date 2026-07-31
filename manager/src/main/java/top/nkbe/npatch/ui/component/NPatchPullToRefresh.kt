package top.nkbe.npatch.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.PullToRefresh
import io.github.suqi8.coui.kmp.basic.PullToRefreshDefaults
import io.github.suqi8.coui.kmp.basic.PullToRefreshState
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.rememberPullToRefreshState
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun NPatchPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    pullToRefreshState: PullToRefreshState = rememberPullToRefreshState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    topAppBarScrollBehavior: ScrollBehavior? = null,
    color: Color = COUITheme.colorScheme.primary,
    circleSize: Dp = PullToRefreshDefaults.circleSize,
    refreshTexts: List<String> = PullToRefreshDefaults.refreshTexts,
    refreshTextStyle: TextStyle = PullToRefreshDefaults.refreshTextStyle,
    content: @Composable () -> Unit,
) {
    PullToRefresh(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        pullToRefreshState = pullToRefreshState,
        contentPadding = contentPadding,
        topAppBarScrollBehavior = topAppBarScrollBehavior,
        color = color,
        circleSize = circleSize,
        refreshTexts = refreshTexts,
        refreshTextStyle = refreshTextStyle,
        content = content,
    )
}
