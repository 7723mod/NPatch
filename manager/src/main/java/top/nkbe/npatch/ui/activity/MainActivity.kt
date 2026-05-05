package top.nkbe.npatch.ui.activity

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import coil.compose.AsyncImage
import top.nkbe.npatch.LSPApplication
import top.nkbe.npatch.config.ThemeConfig
import top.nkbe.npatch.config.ThemeMode
import top.nkbe.npatch.ui.page.AboutScreen
import top.nkbe.npatch.ui.page.LocalNavigator
import top.nkbe.npatch.ui.page.MainScreen
import top.nkbe.npatch.ui.page.Navigator
import top.nkbe.npatch.ui.page.NewPatchScreen
import top.nkbe.npatch.ui.page.RepositoryDetailScreen
import top.nkbe.npatch.ui.page.Route
import top.nkbe.npatch.ui.page.SelectAppsScreen
import top.nkbe.npatch.ui.theme.LSPTheme
import top.nkbe.npatch.ui.util.LocalBackgroundImagePath
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

        checkAndRequestPermissions()

        setContent {
            val systemIsDark = isSystemInDarkTheme()
            val context = LocalContext.current

            val themeState by ThemeConfig.getThemeFlow(context).collectAsState(
                initial = top.nkbe.npatch.config.ThemeSettings(
                    backgroundImageUri = "",
                    useMonet = false,
                    customColor = 0xFF007AFF.toInt(),
                    themeMode = ThemeMode.SYSTEM
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
                CompositionLocalProvider(LocalBackgroundImagePath provides themeState.backgroundImageUri) {
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
                        val backStack = remember { mutableStateListOf<NavKey>(Route.Main) }
                        val navigator = remember { Navigator(backStack) }

                        CompositionLocalProvider(
                            LocalSnackbarHost provides snackbarHostState,
                            LocalNavigator provides navigator
                        ) {
                            NavDisplay(
                                backStack = backStack,
                                onBack = { navigator.pop() },
                                entryProvider = entryProvider {
                                    entry<Route.Main> { MainScreen(navigator) }

                                    entry<Route.About> {
                                        AboutScreen(onBack = { navigator.pop() })
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

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 (SDK 30) 以上請求 "所有檔案存取權"
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            // Android 10 以下請求傳統讀寫權限
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    1001
                )
            }
        }
    }
}
