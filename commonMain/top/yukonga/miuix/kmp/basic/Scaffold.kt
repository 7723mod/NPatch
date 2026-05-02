// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.basic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperDropdown
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.LocalDialogStates
import top.yukonga.miuix.kmp.utils.LocalPopupStates
import top.yukonga.miuix.kmp.utils.LocalRootDialogStates
import top.yukonga.miuix.kmp.utils.LocalRootPopupStates
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost

/**
 * A [Scaffold] component with Miuix style.
 *
 * This implements the basic miuix design visual layout structure.
 *
 * To show a [Snackbar], use [SnackbarHostState.showSnackbar].
 *
 * @param modifier the [Modifier] to be applied to this scaffold.
 * @param topBar top app bar of the screen.
 * @param bottomBar bottom bar of the screen.
 * @param floatingActionButton floating action button of the screen.
 * @param floatingActionButtonPosition position of the floating action button.
 * @param floatingToolbar floating toolbar of the screen.
 * @param floatingToolbarPosition position of the floating toolbar.
 * @param snackbarHost component to host [Snackbar]s that are pushed to be shown via
 *   [SnackbarHostState.showSnackbar], typically a [SnackbarHost].
 * @param popupHost component to host [SuperDropdown]s & [SuperDialog]s that are pushed to
 * be show, typically a [MiuixPopupHost].
 * @param containerColor the color used for the background of this scaffold. Use [Color.Transparent]
 *   to have no color.
 * @param contentWindowInsets window insets to be passed to [content] slot via [PaddingValues]
 *   params. Scaffold will take the insets into account from the top/bottom only if the [topBar]/
 *   [bottomBar] are not present, as the scaffold expect [topBar]/[bottomBar] to handle insets
 *   instead. Any insets consumed by other insets padding modifiers or [consumeWindowInsets] on a
 *   parent layout will be excluded from [contentWindowInsets].
 * @param content content of the screen. The lambda receives a [PaddingValues] that should be
 *   applied to the content root via [Modifier.padding] and [Modifier.consumeWindowInsets] to
 *   properly offset top and bottom bars. If using [Modifier.verticalScroll], apply this modifier to
 *   the child of the scroll, and not on the scroll itself.
 */
@Composable
fun Scaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    floatingToolbar: @Composable () -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    snackbarHost: @Composable () -> Unit = {},
    popupHost: @Composable () -> Unit = { MiuixPopupHost() },
    containerColor: Color = MiuixTheme.colorScheme.surface,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    content: @Composable (PaddingValues) -> Unit,
) {
    val safeInsets = remember(contentWindowInsets) { MutableWindowInsets(contentWindowInsets) }
    val popupStates = remember { mutableStateListOf<MiuixPopupUtils.PopupState>() }
    val dialogStates = remember { mutableStateListOf<MiuixPopupUtils.DialogState>() }
    val parentRootDialogStates = LocalRootDialogStates.current
    val rootDialogStates = parentRootDialogStates ?: dialogStates
    val parentRootPopupStates = LocalRootPopupStates.current
    val rootPopupStates = parentRootPopupStates ?: popupStates
    Surface(
        modifier = modifier.onConsumedWindowInsetsChanged { consumedWindowInsets ->
            // Exclude currently consumed window insets from user provided contentWindowInsets
            safeInsets.insets = contentWindowInsets.exclude(consumedWindowInsets)
        },
        color = containerColor,
    ) {
        CompositionLocalProvider(
            LocalPopupStates provides popupStates,
            LocalDialogStates provides dialogStates,
            LocalRootDialogStates provides rootDialogStates,
            LocalRootPopupStates provides rootPopupStates,
        ) {
            ScaffoldLayout(
                topBar = topBar,
                bottomBar = bottomBar,
                content = content,
                snackbar = snackbarHost,
                floatingActionButton = floatingActionButton,
                floatingActionButtonPosition = floatingActionButtonPosition,
                floatingToolbar = floatingToolbar,
                floatingToolbarPosition = floatingToolbarPosition,
                popup = popupHost,
                contentWindowInsets = safeInsets,
            )
        }
    }
}

/**
 * Layout for a [Scaffold]'s content.
 *
 * @param topBar the content to place at the top of the [Scaffold], typically a [TopAppBar]
 * @param snackbar the [Snackbar] displayed on top of the [content].
 * @param bottomBar the content to place at the bottom of the [Scaffold], on top of the [content],
 *   typically a [NavigationBar].
 * @param floatingActionButton the [FloatingActionButton] displayed on top of the [content], below the [snackbar] and
 *   above the [NavigationBar]
 * @param floatingActionButtonPosition [FabPosition] for the FAB (if present).
 * @param floatingToolbar the [FloatingToolbar] displayed on top of the [content].
 * @param floatingToolbarPosition [ToolbarPosition] for the floating toolbar (if present).
 * @param popup the [MiuixPopupHost] displayed on top of the [content].
 * @param content the main 'body' of the [Scaffold].
 * @param contentWindowInsets the [WindowInsets] to apply to the [content].
 */
@Composable
private fun ScaffoldLayout(
    topBar: @Composable () -> Unit,
    snackbar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    floatingActionButtonPosition: FabPosition,
    floatingToolbar: @Composable () -> Unit,
    floatingToolbarPosition: ToolbarPosition,
    popup: @Composable () -> Unit,
    contentWindowInsets: WindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    // Create the backing value for the content padding
    // These values will be updated during measurement, but before subcomposing the body content
    // Remembering and updating a single PaddingValues avoids needing to recompose when the values
    // change
    val contentPadding = remember {
        object : PaddingValues {
            var paddingHolder by mutableStateOf(PaddingValues(0.dp))

            override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = paddingHolder.calculateLeftPadding(layoutDirection)

            override fun calculateTopPadding(): Dp = paddingHolder.calculateTopPadding()

            override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = paddingHolder.calculateRightPadding(layoutDirection)

            override fun calculateBottomPadding(): Dp = paddingHolder.calculateBottomPadding()
        }
    }
    val popupContent: @Composable () -> Unit = remember(popup) { { Box { popup() } } }
    val topBarContent: @Composable () -> Unit = remember(topBar) { { Box { topBar() } } }
    val snackbarContent: @Composable () -> Unit = remember(snackbar) { { Box { snackbar() } } }
    val floatingActionButtonContent: @Composable () -> Unit =
        remember(floatingActionButton) { { Box { floatingActionButton() } } }
    val floatingToolbarContent: @Composable () -> Unit = remember(floatingToolbar) { { Box { floatingToolbar() } } }
    val bodyContent: @Composable () -> Unit = remember(content, contentPadding) { { Box { content(contentPadding) } } }
    val bottomBarContent: @Composable () -> Unit = remember(bottomBar) { { Box { bottomBar() } } }
    SubcomposeLayout { constraints ->
        val layoutWidth = constraints.maxWidth
        val layoutHeight = constraints.maxHeight

        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)

        val topInset = contentWindowInsets.getTop(this@SubcomposeLayout)
        val leftInset = contentWindowInsets.getLeft(this@SubcomposeLayout, layoutDirection)
        val rightInset = contentWindowInsets.getRight(this@SubcomposeLayout, layoutDirection)
        val bottomInset = contentWindowInsets.getBottom(this@SubcomposeLayout)

        // Measure Popups first (highest z-index)
        val popupPlaceables =
            subcompose(ScaffoldLayoutContent.Popup, popupContent)
                .first()
                .measure(looseConstraints)

        // Measure TopBar
        val topBarPlaceable =
            subcompose(ScaffoldLayoutContent.TopBar, topBarContent)
                .first()
                .measure(looseConstraints)

        // Measure Snackbar
        val snackbarPlaceable =
            subcompose(ScaffoldLayoutContent.Snackbar, snackbarContent)
                .first()
                .measure(looseConstraints.offset(-leftInset - rightInset, -bottomInset))

        // Measure FAB
        val fabPlaceable =
            subcompose(ScaffoldLayoutContent.Fab, floatingActionButtonContent)
                .first()
                .measure(looseConstraints.offset(-leftInset - rightInset, -bottomInset))
        val isFabEmpty = fabPlaceable.width == 0 && fabPlaceable.height == 0
        val fabPlacement = if (!isFabEmpty) {
            val fabWidth = fabPlaceable.width
            val fabHeight = fabPlaceable.height
            // FAB distance from the left of the layout, taking into account LTR / RTL
            val fabLeftOffset =
                when (floatingActionButtonPosition) {
                    FabPosition.Start -> {
                        if (layoutDirection == LayoutDirection.Ltr) {
                            FabSpacing.roundToPx() + leftInset
                        } else {
                            layoutWidth - FabSpacing.roundToPx() - fabWidth - rightInset
                        }
                    }

                    FabPosition.End,
                    FabPosition.EndOverlay,
                    -> {
                        if (layoutDirection == LayoutDirection.Ltr) {
                            layoutWidth - FabSpacing.roundToPx() - fabWidth - rightInset
                        } else {
                            FabSpacing.roundToPx() + leftInset
                        }
                    }

                    else -> (layoutWidth - fabWidth + leftInset - rightInset) / 2
                }

            FabPlacement(left = fabLeftOffset, width = fabWidth, height = fabHeight)
        } else {
            null
        }

        // Measure BottomBar
        val bottomBarPlaceable =
            subcompose(ScaffoldLayoutContent.BottomBar, bottomBarContent)
                .first()
                .measure(looseConstraints)
        val isBottomBarEmpty = bottomBarPlaceable.width == 0 && bottomBarPlaceable.height == 0
        val fabOffsetFromBottom = fabPlacement?.let {
            if (isBottomBarEmpty || floatingActionButtonPosition == FabPosition.EndOverlay) {
                it.height + FabSpacing.roundToPx() + contentWindowInsets.getBottom(this@SubcomposeLayout)
            } else {
                // Total height is the bottom bar height + the FAB height + the padding
                // between the FAB and bottom bar
                bottomBarPlaceable.height + it.height + FabSpacing.roundToPx()
            }
        }

        val snackbarHeight = snackbarPlaceable.height
        val snackbarOffsetFromBottom =
            if (snackbarHeight != 0) {
                snackbarHeight +
                    (
                        fabOffsetFromBottom
                            ?: bottomBarPlaceable.height.takeIf { !isBottomBarEmpty }
                            ?: contentWindowInsets.getBottom(this@SubcomposeLayout)
                        )
            } else {
                0
            }

        // Measure FloatingToolbar
        val floatingToolbarPlaceable =
            subcompose(ScaffoldLayoutContent.FloatingToolbar, floatingToolbarContent)
                .first()
                .measure(looseConstraints.offset(-leftInset - rightInset, -bottomInset))

        val isFloatingToolbarEmpty = floatingToolbarPlaceable.width == 0 && floatingToolbarPlaceable.height == 0

        // Update the backing state for the content padding before subcomposing the body
        val insets = contentWindowInsets.asPaddingValues(this)
        contentPadding.paddingHolder =
            PaddingValues(
                top =
                if (topBarPlaceable.width == 0 && topBarPlaceable.height == 0) {
                    insets.calculateTopPadding()
                } else {
                    topBarPlaceable.height.toDp()
                },
                bottom =
                if (isBottomBarEmpty) {
                    insets.calculateBottomPadding()
                } else {
                    bottomBarPlaceable.height.toDp()
                },
                start = insets.calculateStartPadding(layoutDirection),
                end = insets.calculateEndPadding(layoutDirection),
            )

        // Measure Main Content
        val bodyContentPlaceable =
            subcompose(ScaffoldLayoutContent.MainContent, bodyContent)
                .first()
                .measure(looseConstraints)

        layout(layoutWidth, layoutHeight) {
            // Placing to control drawing order to match default elevation of each placeable
            bodyContentPlaceable.place(0, 0)
            // Place TopBar
            topBarPlaceable.place(0, 0)
            // Place Snackbar
            snackbarPlaceable.place(
                (
                    layoutWidth - snackbarPlaceable.width +
                        contentWindowInsets.getLeft(this@SubcomposeLayout, layoutDirection) -
                        contentWindowInsets.getRight(this@SubcomposeLayout, layoutDirection)
                    ) / 2,
                layoutHeight - snackbarOffsetFromBottom,
            )
            // Place BottomBar
            bottomBarPlaceable.place(0, layoutHeight - bottomBarPlaceable.height)
            // Place FloatingToolbar
            if (!isFloatingToolbarEmpty) {
                val floatingToolbarWidth = floatingToolbarPlaceable.width
                val floatingToolbarHeight = floatingToolbarPlaceable.height

                val alignment = floatingToolbarPosition.toAlignment()

                val availableWidth = layoutWidth - leftInset - rightInset
                val availableHeight = layoutHeight - topBarPlaceable.height - topInset - bottomInset

                val position = alignment.align(
                    IntSize(floatingToolbarWidth, floatingToolbarHeight),
                    IntSize(availableWidth, availableHeight),
                    layoutDirection,
                )

                val x = leftInset + position.x
                val y = topBarPlaceable.height + topInset + position.y - FloatingToolbarSpacing.roundToPx()

                floatingToolbarPlaceable.place(x, y)
            }
            // Place FAB
            fabPlacement?.let { placement ->
                fabPlaceable.place(placement.left, layoutHeight - fabOffsetFromBottom!!)
            }
            // Place Popup
            popupPlaceables.place(0, 0)
        }
    }
}

private enum class ScaffoldLayoutContent {
    TopBar,
    BottomBar,
    Snackbar,
    FloatingToolbar,
    Fab,
    Popup,
    MainContent,
}

/**
 * A [WindowInsets] whose values can change without changing the instance. This is useful to avoid
 * recomposition when [WindowInsets] can change.
 *
 * Copied from [androidx.compose.foundation.layout.MutableWindowInsets], which is marked as
 * experimental and thus cannot be used cross-module.
 */
internal class MutableWindowInsets(initialInsets: WindowInsets = WindowInsets(0, 0, 0, 0)) : WindowInsets {
    /**
     * The [WindowInsets] that are used for [left][getLeft], [top][getTop], [right][getRight], and
     * [bottom][getBottom] values.
     */
    var insets by mutableStateOf(initialInsets)

    override fun getLeft(density: Density, layoutDirection: LayoutDirection): Int = insets.getLeft(density, layoutDirection)

    override fun getTop(density: Density): Int = insets.getTop(density)

    override fun getRight(density: Density, layoutDirection: LayoutDirection): Int = insets.getRight(density, layoutDirection)

    override fun getBottom(density: Density): Int = insets.getBottom(density)
}

@kotlin.jvm.JvmInline
value class FabPosition internal constructor(@Suppress("unused") private val value: Int) {
    companion object {
        /**
         * Position FAB at the bottom of the screen at the start, above the [NavigationBar] (if it
         * exists)
         */
        val Start = FabPosition(0)

        /**
         * Position FAB at the bottom of the screen in the center, above the [NavigationBar] (if it
         * exists)
         */
        val Center = FabPosition(1)

        /**
         * Position FAB at the bottom of the screen at the end, above the [NavigationBar] (if it
         * exists)
         */
        val End = FabPosition(2)

        /**
         * Position FAB at the bottom of the screen at the end, overlaying the [NavigationBar] (if
         * it exists)
         */
        val EndOverlay = FabPosition(3)
    }

    override fun toString(): String = when (this) {
        Start -> "FabPosition.Start"
        Center -> "FabPosition.Center"
        End -> "FabPosition.End"
        else -> "FabPosition.EndOverlay"
    }
}

/**
 * Placement information for a [FloatingActionButton] inside a [Scaffold].
 *
 * @property left the FAB's offset from the left edge of the bottom bar, already adjusted for RTL
 *   support
 * @property width the width of the FAB
 * @property height the height of the FAB
 */
@Immutable
internal class FabPlacement(val left: Int, val width: Int, val height: Int)

// FAB spacing above the bottom bar / bottom of the Scaffold
private val FabSpacing = 12.dp

// FloatingToolbar spacing above the bottom of the Scaffold
private val FloatingToolbarSpacing = 4.dp

// Add Alignment.vertical property helper if not available
internal val Alignment.vertical: Alignment.Vertical
    get() = when (this) {
        Alignment.TopStart, Alignment.TopCenter, Alignment.TopEnd -> Alignment.Top
        Alignment.CenterStart, Alignment.Center, Alignment.CenterEnd -> Alignment.CenterVertically
        Alignment.BottomStart, Alignment.BottomCenter, Alignment.BottomEnd -> Alignment.Bottom
        else -> Alignment.CenterVertically // Default or throw error
    }

// Add Alignment.horizontal property helper if not available
internal val Alignment.horizontal: Alignment.Horizontal
    get() = when (this) {
        Alignment.TopStart, Alignment.CenterStart, Alignment.BottomStart -> Alignment.Start
        Alignment.TopCenter, Alignment.Center, Alignment.BottomCenter -> Alignment.CenterHorizontally
        Alignment.TopEnd, Alignment.CenterEnd, Alignment.BottomEnd -> Alignment.End
        else -> Alignment.CenterHorizontally // Default or throw error
    }

// Keep the internal toAlignment function for ToolbarPosition here
internal fun ToolbarPosition.toAlignment(): Alignment = when (this) {
    ToolbarPosition.TopStart -> Alignment.TopStart

    ToolbarPosition.CenterStart -> Alignment.CenterStart

    ToolbarPosition.BottomStart -> Alignment.BottomStart

    ToolbarPosition.TopEnd -> Alignment.TopEnd

    ToolbarPosition.CenterEnd -> Alignment.CenterEnd

    ToolbarPosition.BottomEnd -> Alignment.BottomEnd

    ToolbarPosition.TopCenter -> Alignment.TopCenter

    // Added
    ToolbarPosition.BottomCenter -> Alignment.BottomCenter

    else -> Alignment.BottomCenter // Default or throw error
}

/**
 * Represents the position of a floating toolbar within the Scaffold.
 * Used by Scaffold for placement calculations.
 */
@kotlin.jvm.JvmInline
value class ToolbarPosition internal constructor(@Suppress("unused") private val value: Int) {
    companion object {
        /** Position Toolbar at the top start corner. */
        val TopStart = ToolbarPosition(0)

        /** Position Toolbar vertically centered on the start edge. */
        val CenterStart = ToolbarPosition(1)

        /** Position Toolbar at the bottom start corner. */
        val BottomStart = ToolbarPosition(2)

        /** Position Toolbar at the top end corner. */
        val TopEnd = ToolbarPosition(3)

        /** Position Toolbar vertically centered on the end edge. */
        val CenterEnd = ToolbarPosition(4)

        /** Position Toolbar at the bottom end corner. */
        val BottomEnd = ToolbarPosition(5)

        /** Position Toolbar horizontally centered along the top edge. */
        // Added KDoc
        val TopCenter = ToolbarPosition(6)

        /** Position Toolbar horizontally centered along the bottom edge. */
        val BottomCenter = ToolbarPosition(7)
    }

    override fun toString(): String = when (this) {
        TopStart -> "ToolbarPosition.TopStart"
        CenterStart -> "ToolbarPosition.CenterStart"
        BottomStart -> "ToolbarPosition.BottomStart"
        TopEnd -> "ToolbarPosition.TopEnd"
        CenterEnd -> "ToolbarPosition.CenterEnd"
        BottomEnd -> "ToolbarPosition.BottomEnd"
        TopCenter -> "ToolbarPosition.TopCenter"
        else -> "ToolbarPosition.BottomCenter"
    }
}
