package top.nkbe.npatch.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.tooling.preview.Preview
import io.github.suqi8.coui.kmp.overlay.OverlayLoadingDialog

@Composable
fun LoadingDialog(
    show: MutableState<Boolean> = mutableStateOf(true),
    title: String = ""
) {
    OverlayLoadingDialog(
        text = title,
        show = show.value,
        onDismissRequest = {},
    )
}

@Preview
@Composable
private fun LoadingDialogPreview() {
    LoadingDialog()
}
