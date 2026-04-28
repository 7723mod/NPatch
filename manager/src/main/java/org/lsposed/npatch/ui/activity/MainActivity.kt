package org.lsposed.npatch.ui.activity

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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import org.lsposed.npatch.LSPApplication
import org.lsposed.npatch.ui.page.LocalNavigator
import org.lsposed.npatch.ui.page.MainScreen
import org.lsposed.npatch.ui.page.Navigator
import org.lsposed.npatch.ui.page.NewPatchScreen
import org.lsposed.npatch.ui.page.RepositoryDetailScreen
import org.lsposed.npatch.ui.page.Route
import org.lsposed.npatch.ui.page.SelectAppsScreen
import org.lsposed.npatch.ui.theme.LSPTheme
import org.lsposed.npatch.ui.util.LocalSnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState

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
            val isDark = isSystemInDarkTheme()

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

            LSPTheme(isDarkTheme = isDark) {
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
                            entry<Route.Main> {
                                MainScreen(navigator)
                            }

                            entry<Route.NewPatch> { route ->
                                NewPatchScreen(
                                    id = route.id,
                                    data = route.data
                                )
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
