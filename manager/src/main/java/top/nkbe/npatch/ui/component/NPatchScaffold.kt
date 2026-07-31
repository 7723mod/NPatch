package top.nkbe.npatch.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import io.github.suqi8.coui.kmp.basic.FabPosition
import io.github.suqi8.coui.kmp.basic.Scaffold as COUIScaffold
import io.github.suqi8.coui.kmp.basic.ToolbarPosition
import io.github.suqi8.coui.kmp.utils.COUIPopupUtils.Companion.COUIPopupHost

@Composable
fun NPatchScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    snackbarHost: @Composable () -> Unit = {},
    popupHost: @Composable () -> Unit = { COUIPopupHost() },
    containerColor: Color = Color.Transparent,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    COUIScaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = floatingActionButtonPosition,
        floatingToolbar = floatingToolbar,
        floatingToolbarPosition = floatingToolbarPosition,
        snackbarHost = snackbarHost,
        popupHost = popupHost,
        containerColor = containerColor,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}
