package top.nkbe.npatch.ui.page

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.util.backgroundAwareColor
import top.nkbe.npatch.ui.util.backgroundAwareHazeStyle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.utils.PressFeedbackType
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import io.github.suqi8.coui.kmp.utils.scrollEndHaptic

private data class AboutLink(
    val title: String,
    val summary: String,
    val url: String,
    val icon: ImageVector? = null,
    val imageUrl: String? = null,
    val imageRes: Int? = null,
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = COUIScrollBehavior()
    val hazeState = rememberHazeState()
    val hazeStyle = backgroundAwareHazeStyle()
    val showTopBarContent by remember {
        derivedStateOf { scrollBehavior.state.collapsedFraction == 0f }
    }

    NPatchScaffold(
        topBar = {
            NPatchTopAppBar(
                title = if (showTopBarContent) stringResource(R.string.home_about) else "",
                navigationIcon = {
                    if (showTopBarContent) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = COUIIcons.Regular.Back,
                                contentDescription = stringResource(R.string.nav_back),
                                tint = COUITheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                hazeState = hazeState,
                hazeStyle = hazeStyle,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                end = 12.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null
        ) {
            item {
                ModuleIntroCard()
            }

            item {
                AuthorCard(
                    onClick = { context.openUri(AUTHOR_GITHUB_URL) }
                )
            }

            item {
                DisclaimerCard()
            }

            item {
                LinksCard(
                    onLinkClick = context::openUri
                )
            }

            item {
                AcknowledgmentsCard(
                    onLinkClick = context::openUri
                )
            }
        }
    }
}

@Composable
private fun ModuleIntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(),
        showIndication = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_playstore),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = "NPatch",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_description),
                fontSize = COUITheme.textStyles.body2.fontSize,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AuthorCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(),
    ) {
        ArrowPreference(
            title = "NkBe",
            summary = stringResource(R.string.about_author_summary),
            startAction = {
                AsyncImage(
                    model = crossfadeModel(AUTHOR_AVATAR_URL),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            },
            onClick = onClick
        )
    }
}

@Composable
private fun DisclaimerCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(),
        showIndication = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            AboutSectionHeader(
                icon = Icons.Outlined.Security,
                title = stringResource(R.string.about_disclaimer_title)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.about_disclaimer_body),
                fontSize = COUITheme.textStyles.body2.fontSize,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Start
            )
        }
    }
}

@Composable
private fun LinksCard(onLinkClick: (String) -> Unit) {
    val links = rememberAboutLinks()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(),
        showIndication = false,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            AboutSectionHeader(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                icon = Icons.Outlined.Public,
                title = stringResource(R.string.about_links_title)
            )
            links.forEach { link ->
                ArrowPreference(
                    title = link.title,
                    summary = link.summary,
                    startAction = {
                        LinkIcon(link)
                    },
                    onClick = { onLinkClick(link.url) }
                )
            }
        }
    }
}

@Composable
private fun AcknowledgmentsCard(onLinkClick: (String) -> Unit) {
    val contributors = rememberAcknowledgmentLinks()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(),
        showIndication = false,
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            AboutSectionHeader(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                icon = Icons.Outlined.Favorite,
                title = stringResource(R.string.about_acknowledgments_title)
            )
            contributors.forEach { contributor ->
                ArrowPreference(
                    title = contributor.title,
                    summary = contributor.summary,
                    startAction = {
                        LinkIcon(contributor)
                    },
                    onClick = { onLinkClick(contributor.url) }
                )
            }
        }
    }
}

@Composable
private fun AboutSectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = COUITheme.colorScheme.primary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = COUITheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LinkIcon(link: AboutLink) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundAwareColor(COUITheme.colorScheme.primaryContainer)),
        contentAlignment = Alignment.Center
    ) {
        when {
            link.imageUrl != null -> {
                AsyncImage(
                    model = crossfadeModel(link.imageUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            link.imageRes != null -> {
                Image(
                    painter = painterResource(link.imageRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            link.icon != null -> {
                Icon(
                    imageVector = link.icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = COUITheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun crossfadeModel(url: String): ImageRequest {
    val context = LocalContext.current
    return remember(url, context) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(true)
            .build()
    }
}

@Composable
private fun rememberAboutLinks(): List<AboutLink> {
    val websiteTitle = stringResource(R.string.about_official_website)
    val websiteSummary = stringResource(R.string.about_link_website_summary)
    val githubSummary = stringResource(R.string.about_link_github_summary)
    val telegramSummary = stringResource(R.string.about_link_telegram_summary)

    return remember(websiteTitle, websiteSummary, githubSummary, telegramSummary) {
        listOf(
            AboutLink(
                title = websiteTitle,
                summary = websiteSummary,
                url = ABOUT_WEBSITE_URL,
                icon = Icons.Outlined.Public
            ),
            AboutLink(
                title = "GitHub",
                summary = githubSummary,
                url = GITHUB_URL,
                icon = Icons.Outlined.Code
            ),
            AboutLink(
                title = "Telegram",
                summary = telegramSummary,
                url = TELEGRAM_URL,
                icon = Icons.AutoMirrored.Outlined.Send
            )
        )
    }
}

@Composable
private fun rememberAcknowledgmentLinks(): List<AboutLink> {
    val rovo89 = stringResource(R.string.about_ack_rovo89_summary)
    val lsposed = stringResource(R.string.about_ack_lsposed_team_summary)
    val jingMatrix = stringResource(R.string.about_ack_jingmatrix_summary)
    val lspatch = stringResource(R.string.about_ack_lspatch_summary)
    val libxposed = stringResource(R.string.about_ack_libxposed_summary)
    val winter = stringResource(R.string.about_ack_winter_summary)
    val m558 = stringResource(R.string.about_ack_m558_summary)
    val community = stringResource(R.string.about_ack_community_summary)

    return remember(rovo89, jingMatrix, lsposed, lspatch, libxposed, winter, m558, community) {
        listOf(
            AboutLink(
                title = "rovo89",
                summary = rovo89,
                url = "https://github.com/rovo89/XposedBridge",
                imageUrl = ROVO89_AVATAR_URL
            ),
            AboutLink(
                title = "JingMatrix",
                summary = jingMatrix,
                url = "https://github.com/JingMatrix/Vector",
                imageUrl = JING_MATRIX_AVATAR_URL
            ),
            AboutLink(
                title = "LSPosed",
                summary = lsposed,
                url = "https://github.com/LSPosed/LSPosed",
                imageUrl = LSPOSED_TEAM_AVATAR_URL
            ),
            AboutLink(
                title = "LSPatch",
                summary = lspatch,
                url = "https://github.com/LSPosed/LSPatch",
                imageUrl = LSPATCH_AVATAR_URL
            ),
            AboutLink(
                title = "libxposed",
                summary = libxposed,
                url = "https://github.com/libxposed/api",
                imageUrl = LIBXPOSED_AVATAR_URL
            ),
            AboutLink(
                title = "winter",
                summary = winter,
                url = TELEGRAM_URL,
                imageRes = R.drawable.winter
            ),
            AboutLink(
                title = "M558",
                summary = m558,
                url = TELEGRAM_URL,
                imageRes = R.drawable.m558
            ),
            AboutLink(
                title = "Community",
                summary = community,
                url = GITHUB_URL,
                icon = Icons.Outlined.Favorite
            )
        )
    }
}

private fun Context.openUri(uri: String) {
    startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
}

private const val ABOUT_WEBSITE_URL = "https://www.nkbe.top"
private const val GITHUB_URL = "https://github.com/7723mod/NPatch"
private const val TELEGRAM_URL = "https://t.me/NPatch"
private const val AUTHOR_GITHUB_URL = "https://github.com/HSSkyBoy"
private const val AUTHOR_AVATAR_URL = "https://avatars.githubusercontent.com/u/122550437?s=256"
private const val ROVO89_AVATAR_URL = "https://avatars.githubusercontent.com/u/1573299?s=256"
private const val JING_MATRIX_AVATAR_URL = "https://avatars.githubusercontent.com/u/24476093?s=256"
private const val LSPOSED_TEAM_AVATAR_URL = "https://avatars.githubusercontent.com/u/75879071?s=256&v=4"
private const val LSPATCH_AVATAR_URL =
    "https://raw.githubusercontent.com/LSPosed/LSPatch/refs/heads/master/manager/src/main/ic_launcher-playstore.png"
private const val LIBXPOSED_AVATAR_URL = "https://avatars.githubusercontent.com/u/85155136?s=128"
