package top.nkbe.npatch.ui.activity

import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import coil.compose.AsyncImage
import top.nkbe.npatch.LSPApplication
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.ThemeConfig
import top.nkbe.npatch.config.ThemeMode
import top.nkbe.npatch.config.ThemeSettings
import top.nkbe.npatch.ui.page.AboutScreen
import top.nkbe.npatch.ui.page.LocalNavigator
import top.nkbe.npatch.ui.page.MainTab
import top.nkbe.npatch.ui.page.MainScreen
import top.nkbe.npatch.ui.page.Navigator
import top.nkbe.npatch.ui.page.NewPatchScreen
import top.nkbe.npatch.ui.page.RepositoryDetailScreen
import top.nkbe.npatch.ui.page.Route
import top.nkbe.npatch.ui.page.SelectAppsScreen
import top.nkbe.npatch.ui.page.WelcomeScreen
import top.nkbe.npatch.ui.theme.LSPTheme
import top.nkbe.npatch.ui.util.LocalBackgroundImagePath
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBar
import top.nkbe.npatch.ui.util.LocalFloatingGlassBottomBarBlur
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "") ?: ""
        super.attachBaseContext(LSPApplication.applyLocale(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ) { false },
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ) { false }
        )

        setContent {
            val systemIsDark = isSystemInDarkTheme()
            val context = LocalContext.current
            val supportsFloatingGlassBottomBarBlur = ThemeConfig.isFloatingGlassBottomBarBlurSupported()

            val themeState by ThemeConfig.getThemeFlow(context).collectAsState(
                initial = ThemeSettings(
                    backgroundImageUri = "",
                    useMonet = false,
                    customColor = 0xFF007AFF.toInt(),
                    themeMode = ThemeMode.SYSTEM,
                    useFloatingGlassBottomBar = false,
                    useFloatingGlassBottomBarBlur = supportsFloatingGlassBottomBarBlur,
                )
            )
            val isDark = when (themeState.themeMode) {
                ThemeMode.SYSTEM -> systemIsDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { isDark },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { isDark }
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            LSPTheme(
                isDarkTheme = isDark,
                useMonet = themeState.useMonet,
                customColor = themeState.customColor
            ) {
                CompositionLocalProvider(
                    LocalBackgroundImagePath provides themeState.backgroundImageUri,
                    LocalFloatingGlassBottomBar provides themeState.useFloatingGlassBottomBar,
                    LocalFloatingGlassBottomBarBlur provides (
                        themeState.useFloatingGlassBottomBarBlur && supportsFloatingGlassBottomBarBlur
                    ),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Crossfade(targetState = themeState.backgroundImageUri, label = "global_background") { path ->
                            if (path.isNotEmpty()) {
                                AsyncImage(
                                    model = path,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .blur(20.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.35f))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MiuixTheme.colorScheme.background)
                                )
                            }
                        }

                        val snackbarHostState = remember { SnackbarHostState() }
                        val startRoute = remember {
                            if (Configs.welcomeSeen) Route.Main() else Route.Welcome()
                        }
                        val backStack = remember { mutableStateListOf<NavKey>(startRoute) }
                        val navigator = remember { Navigator(backStack) }
                        val startMainRoute = startRoute as? Route.Main
                        var selectedMainTab by rememberSaveable {
                            mutableIntStateOf(startMainRoute?.initialTab ?: MainTab.Home.ordinal)
                        }
                        var selectedManageTab by rememberSaveable {
                            mutableIntStateOf(startMainRoute?.initialManageTab ?: 0)
                        }

                        CompositionLocalProvider(
                            LocalSnackbarHost provides snackbarHostState,
                            LocalNavigator provides navigator
                        ) {
                            NavDisplay(
                                backStack = backStack,
                                onBack = { navigator.pop() },
                                entryProvider = entryProvider {
                                    entry<Route.Main> {
                                        MainScreen(
                                            navigator = navigator,
                                            selectedTab = selectedMainTab,
                                            selectedManageTab = selectedManageTab,
                                            onSelectedTabChange = { selectedMainTab = it },
                                            onSelectedManageTabChange = { selectedManageTab = it }
                                        )
                                    }

                                    entry<Route.About> {
                                        AboutScreen(onBack = { navigator.pop() })
                                    }

                                    entry<Route.Welcome> { route ->
                                        WelcomeScreen(
                                            reviewMode = route.reviewMode,
                                            onFinish = {
                                                backStack.clear()
                                                backStack.add(Route.Main())
                                            },
                                            onReturn = { navigator.pop() }
                                        )
                                    }

                                    entry<Route.NewPatch> { route ->
                                        NewPatchScreen(id = route.id, data = route.data)
                                    }

                                    entry<Route.SelectApps> { route ->
                                        SelectAppsScreen(
                                            multiSelect = route.multiSelect,
                                            initialSelected = route.initialSelected
                                        )
                                    }

                                    entry<Route.RepoDetail> { route ->
                                        RepositoryDetailScreen(
                                            packageName = route.packageName,
                                            onBack = { navigator.pop() }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
