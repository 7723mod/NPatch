package top.nkbe.npatch.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import top.nkbe.npatch.ui.util.SampleStringProvider
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

@Preview
@Composable
fun CenterTopBar(@PreviewParameter(SampleStringProvider::class, 1) text: String) {
    TopAppBar(
        title = text
    )
}
