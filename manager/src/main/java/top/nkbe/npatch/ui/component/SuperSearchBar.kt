@file:SuppressLint("LocalContextGetResourceValueCall")

package top.nkbe.npatch.ui.component

import android.R
import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.zIndex
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.basic.Search
import io.github.suqi8.coui.kmp.icon.basic.SearchCleanup
import io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme
import io.github.suqi8.coui.kmp.utils.overScrollVertical
import top.nkbe.npatch.ui.util.BG_SURFACE_ALPHA
import top.nkbe.npatch.ui.util.LocalBackgroundImagePath

// Search Status Class
@Stable
class SearchStatus(val label: String) {
    var searchText by mutableStateOf("")
    var current by mutableStateOf(Status.COLLAPSED)

    var offsetY by mutableStateOf(0.dp)
    var resultStatus by mutableStateOf(ResultStatus.DEFAULT)

    fun isExpand() = current == Status.EXPANDED
    fun isCollapsed() = current == Status.COLLAPSED
    fun shouldExpand() = current == Status.EXPANDED || current == Status.EXPANDING
    fun shouldCollapsed() = current == Status.COLLAPSED || current == Status.COLLAPSING
    fun isAnimatingExpand() = current == Status.EXPANDING

    // 动画完成回调
    fun onAnimationComplete() {
        current = when (current) {
            Status.EXPANDING -> Status.EXPANDED
            Status.COLLAPSING -> {
                searchText = ""
                Status.COLLAPSED
            }

            else -> current
        }
    }

    @Composable
    fun TopAppBarAnim(
        modifier: Modifier = Modifier,
        visible: Boolean = shouldCollapsed(),
        blurVisible: Boolean = false,
        hazeState: HazeState? = null,
        hazeStyle: HazeBlurStyle? = null,
        content: @Composable () -> Unit
    ) {
        val topAppBarAlpha = animateFloatAsState(
            if (visible) 1f else 0f,
            animationSpec = tween(if (visible) 550 else 0, easing = FastOutSlowInEasing),
        )
        val hazeModifier = if (blurVisible && hazeState != null && hazeStyle != null) {
            Modifier.hazeEffect(hazeState) {
                blurEffect {
                    style = hazeStyle
                    blurRadius = 30.dp
                    noiseFactor = 0f
                }
            }
        } else {
            Modifier
        }
        Box(
            modifier = modifier
                .alpha(topAppBarAlpha.value)
                .then(hazeModifier)
        ) {
            Box(
                modifier = Modifier
            ) { content() }
        }
    }

    enum class Status { EXPANDED, EXPANDING, COLLAPSED, COLLAPSING }
    enum class ResultStatus { DEFAULT, EMPTY, LOAD, SHOW }
}

// Search Box Composable
@Composable
fun SearchStatus.SearchBox(
    collapseBar: @Composable (SearchStatus, Dp, PaddingValues) -> Unit = { searchStatus, topPadding, innerPadding ->
        SearchBarFake(searchStatus.label, topPadding, innerPadding)
    },
    searchBarTopPadding: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    hazeState: HazeState,
    hazeStyle: HazeBlurStyle,
    content: @Composable (MutableState<Dp>) -> Unit
) {
    val searchStatus = this
    val density = LocalDensity.current

    animateFloatAsState(if (searchStatus.shouldCollapsed()) 1f else 0f)

    val offsetY = remember { mutableIntStateOf(0) }
    val boxHeight = remember { mutableStateOf(0.dp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(10f)
            .alpha(if (searchStatus.isCollapsed()) 1f else 0f)
            .offset(y = contentPadding.calculateTopPadding())
            .onGloballyPositioned {
                it.positionInWindow().y.apply {
                    offsetY.intValue = (this@apply * 0.9).toInt()
                    with(density) {
                        searchStatus.offsetY = this@apply.toDp()
                        boxHeight.value = it.size.height.toDp()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { searchStatus.current = SearchStatus.Status.EXPANDING }
            }
            .hazeEffect(hazeState) {
                blurEffect {
                    style = hazeStyle
                    blurRadius = 30.dp
                    noiseFactor = 0f
                }
            }
    ) {
        collapseBar(searchStatus, searchBarTopPadding, contentPadding)
    }
    Box {
        AnimatedVisibility(
            visible = searchStatus.shouldCollapsed(),
            enter = fadeIn(tween(300, easing = LinearOutSlowInEasing)) + slideInVertically(
                tween(
                    300,
                    easing = LinearOutSlowInEasing
                )
            ) { -offsetY.intValue },
            exit = fadeOut(tween(300, easing = LinearOutSlowInEasing)) + slideOutVertically(
                tween(
                    300,
                    easing = LinearOutSlowInEasing
                )
            ) { -offsetY.intValue }
        ) {
            content(boxHeight)
        }
    }
}

// Search Pager Composable
@Composable
fun SearchStatus.SearchPager(
    defaultResult: @Composable () -> Unit,
    expandBar: @Composable (SearchStatus, Dp) -> Unit = { searchStatus, padding ->
        SearchBar(searchStatus, padding)
    },
    searchBarTopPadding: Dp = 12.dp,
    result: LazyListScope.() -> Unit
) {
    val searchStatus = this
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val hasBackgroundImage = LocalBackgroundImagePath.current.isNotEmpty()
    val topPadding by animateDpAsState(
        targetValue = if (searchStatus.shouldExpand()) {
            systemBarsPadding + 5.dp
        } else {
            max(searchStatus.offsetY, 0.dp)
        },
        animationSpec = tween(300, easing = LinearOutSlowInEasing)
    ) {
        searchStatus.onAnimationComplete()
    }
    val surfaceAlpha by animateFloatAsState(
        if (searchStatus.shouldExpand()) if (hasBackgroundImage) BG_SURFACE_ALPHA else 1f else 0f,
        animationSpec = tween(200, easing = FastOutSlowInEasing)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(5f)
            .background(colorScheme.surface.copy(alpha = surfaceAlpha))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = topPadding)
                .then(
                    if (!searchStatus.isCollapsed()) {
                        if (hasBackgroundImage) {
                            Modifier.background(colorScheme.surface.copy(alpha = BG_SURFACE_ALPHA))
                        } else {
                            Modifier.background(colorScheme.surface)
                        }
                    } else Modifier
                ),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!searchStatus.isCollapsed()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (hasBackgroundImage) {
                                Modifier.background(colorScheme.surface.copy(alpha = BG_SURFACE_ALPHA))
                            } else {
                                Modifier.background(colorScheme.surface)
                            }
                        )
                ) {
                    expandBar(searchStatus, searchBarTopPadding)
                }
            }
            AnimatedVisibility(
                visible = searchStatus.isExpand() || searchStatus.isAnimatingExpand(),
                enter = expandHorizontally() + slideInHorizontally(initialOffsetX = { it }),
                exit = shrinkHorizontally() + slideOutHorizontally(targetOffsetX = { it })
            ) {
                Text(
                    text = stringResource(R.string.cancel),
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp, end = 16.dp, top = searchBarTopPadding)
                        .semantics { role = Role.Button }
                        .clickable(
                            interactionSource = null,
                            enabled = searchStatus.isExpand(),
                            indication = null
                        ) {
                            searchStatus.searchText = ""
                            searchStatus.current = SearchStatus.Status.COLLAPSING
                        }
                )
                run {
                    val navEventState = rememberNavigationEventState(NavigationEventInfo.None)
                    NavigationBackHandler(
                        state = navEventState,
                        isBackEnabled = true,
                        onBackCompleted = {
                            searchStatus.searchText = ""
                            searchStatus.current = SearchStatus.Status.COLLAPSING
                        }
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = searchStatus.isExpand(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (searchStatus.resultStatus) {
                SearchStatus.ResultStatus.DEFAULT -> defaultResult()
                SearchStatus.ResultStatus.EMPTY -> {}
                SearchStatus.ResultStatus.LOAD -> {}
                SearchStatus.ResultStatus.SHOW -> LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .overScrollVertical(),
                ) {
                    result()
                }
            }
        }
    }
}

@Composable
fun SearchBar(
    searchStatus: SearchStatus,
    searchBarTopPadding: Dp = 12.dp,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val imeBottomPadding = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val clearLabel = stringResource(top.nkbe.npatch.R.string.accessibility_clear)

    InputField(
        query = searchStatus.searchText,
        onQueryChange = { searchStatus.searchText = it },
        label = "",
        leadingIcon = {
            Icon(
                imageVector = COUIIcons.Basic.Search,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = colorScheme.onSurfaceContainerHigh,
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                searchStatus.searchText.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                Icon(
                    imageVector = COUIIcons.Basic.SearchCleanup,
                    tint = colorScheme.onSurface,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .padding(start = 8.dp, end = 16.dp)
                        .clearAndSetSemantics {
                            contentDescription = clearLabel
                        }
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) {
                            searchStatus.searchText = ""
                        },
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = searchBarTopPadding, bottom = 6.dp)
            .focusRequester(focusRequester),
        onSearch = { },
        expanded = searchStatus.shouldExpand(),
        onExpandedChange = {
            searchStatus.current = if (it) SearchStatus.Status.EXPANDED else SearchStatus.Status.COLLAPSED
        }
    )
    LaunchedEffect(Unit) {
        if (!expanded && searchStatus.shouldExpand()) {
            focusRequester.requestFocus()
            expanded = true
        }
    }
    LaunchedEffect(searchStatus.shouldExpand(), imeBottomPadding) {
        if (searchStatus.shouldExpand() && imeBottomPadding == 0.dp) {
            focusManager.clearFocus(force = true)
        }
    }
}

@Composable
fun SearchBarFake(
    label: String,
    searchBarTopPadding: Dp = 12.dp,
    innerPadding: PaddingValues = PaddingValues(0.dp)
) {
    val layoutDirection = LocalLayoutDirection.current
    InputField(
        query = "",
        onQueryChange = { },
        label = label,
        leadingIcon = {
            Icon(
                imageVector = COUIIcons.Basic.Search,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .padding(start = 16.dp, end = 8.dp),
                tint = colorScheme.onSurfaceContainerHigh,
            )
        },
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(
                start = innerPadding.calculateStartPadding(layoutDirection),
                end = innerPadding.calculateEndPadding(layoutDirection)
            )
            .padding(top = searchBarTopPadding, bottom = 6.dp)
            .clearAndSetSemantics {
                contentDescription = label
            },
        onSearch = { },
        enabled = false,
        expanded = false,
        onExpandedChange = { }
    )
}
