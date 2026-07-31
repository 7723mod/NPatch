package top.nkbe.npatch.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import top.nkbe.npatch.ui.util.BG_SURFACE_ALPHA
import top.nkbe.npatch.ui.util.LocalBackgroundImagePath
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import top.nkbe.npatch.ui.component.miuix.animation.DampedDragAnimation
import top.nkbe.npatch.ui.component.miuix.animation.InteractiveHighlight
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.abs
import kotlin.math.sign

private val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }
private val FloatingBottomBarItemMinWidth = 76.dp
private val FloatingBottomBarHorizontalPadding = 4.dp
private val FloatingBottomBarVerticalPadding = 4.dp

@Composable
fun RowScope.FloatingGlassBottomBarItem(
    onClick: () -> Unit,
    selected: Boolean = false,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalFloatingBottomBarTabScale.current
    Column(
        modifier
            .defaultMinSize(minWidth = FloatingBottomBarItemMinWidth)
            .clip(CircleShape)
            .clearAndSetSemantics {
                this.selected = selected
                contentDescription = label
            }
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val scale = scale()
                scaleX = scale
                scaleY = scale
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

@Composable
fun FloatingGlassBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: () -> Int,
    onSelected: (index: Int) -> Unit,
    backdrop: Backdrop?,
    tabsCount: Int,
    isBlurEnabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    if (!isBlurEnabled || backdrop == null) {
        FloatingGlassBottomBarFallback(
            modifier = modifier,
            content = content
        )
        return
    }

    val isInLightTheme = !isSystemInDarkTheme()
    val accentColor = COUITheme.colorScheme.primary
    val containerColor = COUITheme.colorScheme.surfaceContainer.copy(alpha = 0.18f)

    val tabsBackdrop = rememberLayerBackdrop()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()

    var tabWidthPx by remember { mutableFloatStateOf(0f) }
    var totalWidthPx by remember { mutableFloatStateOf(0f) }

    val offsetAnimation = remember { Animatable(0f) }
    val panelOffset by remember(density) {
        derivedStateOf {
            if (totalWidthPx == 0f) {
                0f
            } else {
                val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                with(density) {
                    FloatingBottomBarHorizontalPadding.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }
    }

    var currentIndex by remember(selectedIndex) { mutableIntStateOf(selectedIndex()) }

    class DampedDragAnimationHolder {
        var instance: DampedDragAnimation? = null
    }

    val holder = remember { DampedDragAnimationHolder() }
    val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedIndex().toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 78f / 56f,
            canDrag = { offset ->
                val anim = holder.instance ?: return@DampedDragAnimation true
                if (tabWidthPx == 0f) return@DampedDragAnimation false

                val currentValue = anim.value
                val indicatorX = currentValue * tabWidthPx
                val padding = with(density) { FloatingBottomBarHorizontalPadding.toPx() }
                val globalTouchX = if (isLtr) {
                    padding + indicatorX + offset.x
                } else {
                    totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                }
                globalTouchX in 0f..totalWidthPx
            },
            onDragStarted = {},
            onDragStopped = {
                val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabsCount - 1)
                currentIndex = targetIndex
                animateToValue(targetIndex.toFloat())
                animationScope.launch {
                    offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                }
            },
            onDrag = { _, dragAmount ->
                if (tabWidthPx > 0) {
                    updateValue(
                        (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, (tabsCount - 1).toFloat())
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                }
            }
        ).also { holder.instance = it }
    }

    LaunchedEffect(selectedIndex) {
        snapshotFlow { selectedIndex() }.collectLatest { currentIndex = it }
    }
    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { currentIndex }.drop(1).collectLatest { index ->
            dampedDragAnimation.animateToValue(index.toFloat())
            onSelected(index)
        }
    }

    val interactiveHighlight = remember(animationScope, tabWidthPx) {
        InteractiveHighlight(
            animationScope = animationScope,
            position = { size, _ ->
                Offset(
                    if (isLtr) (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                    else size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset,
                    size.height / 2f
                )
            }
        )
    }

    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            Modifier
                .clearAndSetSemantics {}
                .onGloballyPositioned { coords ->
                    totalWidthPx = coords.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { FloatingBottomBarHorizontalPadding.toPx() * 2f }
                    tabWidthPx = contentWidthPx / tabsCount
                }
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        if (isBlurEnabled) {
                            vibrancy()
                            blur(4.dp.toPx())
                            lens(24.dp.toPx(), 24.dp.toPx())
                        }
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = if (isBlurEnabled) 1f else 0f)
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(if (isInLightTheme) 0.06f else 0.12f),
                        )
                    },
                    layerBlock = {
                        if (isBlurEnabled) {
                            val progress = dampedDragAnimation.pressProgress
                            val scale = lerp(1f, 1f + 16.dp.toPx() / size.width, progress)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                    onDrawSurface = { drawRect(containerColor) }
                )
                .then(if (isBlurEnabled) interactiveHighlight.modifier else Modifier)
                .height(64.dp)
                .padding(horizontal = FloatingBottomBarHorizontalPadding, vertical = FloatingBottomBarVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        CompositionLocalProvider(
            LocalFloatingBottomBarTabScale provides {
                if (isBlurEnabled) lerp(1f, 1.2f, dampedDragAnimation.pressProgress) else 1f
            }
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .semantics { invisibleToUser() }
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            if (isBlurEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                vibrancy()
                                blur(4.dp.toPx())
                                lens(24.dp.toPx() * progress, 24.dp.toPx() * progress)
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = if (isBlurEnabled) dampedDragAnimation.pressProgress else 0f)
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(if (isBlurEnabled) interactiveHighlight.modifier else Modifier)
                    .height(56.dp)
                    .padding(horizontal = FloatingBottomBarHorizontalPadding)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }

        if (tabWidthPx > 0f) {
            Box(
                Modifier
                    .padding(horizontal = FloatingBottomBarHorizontalPadding)
                    .graphicsLayer {
                        val contentWidth = totalWidthPx - with(density) { FloatingBottomBarHorizontalPadding.toPx() * 2f }
                        val singleTabWidth = contentWidth / tabsCount
                        val progressOffset = dampedDragAnimation.value * singleTabWidth

                        translationX = if (isLtr) {
                            progressOffset + panelOffset
                        } else {
                            -progressOffset + panelOffset
                        }
                    }
                    .then(if (isBlurEnabled) interactiveHighlight.gestureModifier else Modifier)
                    .then(dampedDragAnimation.modifier)
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { CircleShape },
                        effects = {
                            if (isBlurEnabled) {
                                val progress = dampedDragAnimation.pressProgress
                                lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, true)
                            }
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = if (isBlurEnabled) dampedDragAnimation.pressProgress else 0f)
                        },
                        shadow = { Shadow(alpha = if (isBlurEnabled) dampedDragAnimation.pressProgress else 0f) },
                        innerShadow = {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                alpha = if (isBlurEnabled) dampedDragAnimation.pressProgress else 0f
                            )
                        },
                        layerBlock = {
                            if (isBlurEnabled) {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            }
                        },
                        onDrawSurface = {
                            val progress = if (isBlurEnabled) dampedDragAnimation.pressProgress else 0f
                            drawRect(
                                color = if (isInLightTheme) Color.Black.copy(0.1f) else Color.White.copy(0.1f),
                                alpha = 1f - progress
                            )
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    )
                    .height(56.dp)
                    .width(
                        with(density) {
                            ((totalWidthPx - FloatingBottomBarHorizontalPadding.toPx() * 2f) / tabsCount).toDp()
                        }
                    )
            )
        }
    }
}

@Composable
private fun FloatingGlassBottomBarFallback(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val hasBackgroundImage = LocalBackgroundImagePath.current.isNotEmpty()
    val barColor = if (hasBackgroundImage) {
        COUITheme.colorScheme.surfaceContainer.copy(alpha = BG_SURFACE_ALPHA)
    } else {
        COUITheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)
    }
    Box(
        modifier = modifier.width(IntrinsicSize.Min),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(barColor)
                .height(64.dp)
                .padding(
                    horizontal = FloatingBottomBarHorizontalPadding,
                    vertical = FloatingBottomBarVerticalPadding
                ),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun FloatingGlassBottomBarIcon(
    selected: Boolean,
    selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Icon(
        imageVector = if (selected) selectedIcon else unselectedIcon,
        contentDescription = null,
        tint = COUITheme.colorScheme.onSurface
    )
}

@Composable
fun FloatingGlassBottomBarLabel(
    label: String
) {
    Text(
        text = label,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        color = COUITheme.colorScheme.onSurface,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible
    )
}
