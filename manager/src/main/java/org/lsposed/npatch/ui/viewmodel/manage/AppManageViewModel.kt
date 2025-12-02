package org.lsposed.npatch.ui.viewmodel.manage

import android.content.pm.PackageInstaller
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lsposed.npatch.Patcher
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.share.Constants
import org.lsposed.npatch.share.PatchConfig
import org.lsposed.npatch.ui.util.installApk
import org.lsposed.npatch.ui.util.installApks
import org.lsposed.npatch.ui.viewstate.ProcessingState
import org.lsposed.npatch.util.NPackageManager
import org.lsposed.npatch.util.NPackageManager.AppInfo
import org.lsposed.npatch.util.ShizukuApi
import org.lsposed.patch.util.Logger
import java.io.FileNotFoundException
import java.util.zip.ZipFile

class AppManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ManageViewModel"
        private const val AUTO_REFRESH_INTERVAL = 90_114L
    }

    sealed class ViewAction {
        data class UpdateLoader(val appInfo: AppInfo, val config: PatchConfig) : ViewAction()
        object ClearUpdateLoaderResult : ViewAction()
        data class PerformOptimize(val appInfo: AppInfo) : ViewAction()
        object ClearOptimizeResult : ViewAction()
        object RefreshList : ViewAction()
    }

    // 手動管理狀態，避免實時響應系統廣播導致列表跳動
    var appList: List<Pair<AppInfo, PatchConfig>> by mutableStateOf(emptyList())
        private set

    var isRefreshing by mutableStateOf(false)
        private set

    var updateLoaderState: ProcessingState<Result<Unit>> by mutableStateOf(ProcessingState.Idle)
        private set

    var optimizeState: ProcessingState<Boolean> by mutableStateOf(ProcessingState.Idle)
        private set

    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) Log.d(TAG, msg)
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
        }
    }

    init {
        viewModelScope.launch {
            snapshotFlow { NPackageManager.appList }
                .filter { it.isNotEmpty() }
                .first()
            Log.d(TAG, "Initial data ready, starting auto-refresh loop")
            // 啓動立即加载
            loadData()

            while (true) {
                delay(AUTO_REFRESH_INTERVAL)
                Log.d(TAG, "Auto refreshing app list (90s timer)")
                if (!isRefreshing) {
                    loadData(silent = true)
                }
            }
        }
    }

    fun dispatch(action: ViewAction) {
        viewModelScope.launch {
            when (action) {
                is ViewAction.UpdateLoader -> updateLoader(action.appInfo, action.config)
                is ViewAction.ClearUpdateLoaderResult -> updateLoaderState = ProcessingState.Idle
                is ViewAction.PerformOptimize -> performOptimize(action.appInfo)
                is ViewAction.ClearOptimizeResult -> optimizeState = ProcessingState.Idle
                is ViewAction.RefreshList -> loadData(silent = false)
            }
        }
    }

    // silent 参数用于区分是否显示 loading 状态
    private fun loadData(silent: Boolean = false) {
        if (!silent) isRefreshing = true
        val currentList = NPackageManager.appList.mapNotNull { appInfo ->
            runCatching {
                appInfo.app.metaData?.getString("npatch")?.let {
                    val json = Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8)
                    val config = Gson().fromJson(json, PatchConfig::class.java)
                    if (config?.lspConfig == null) null else appInfo to config
                }
            }.getOrNull()
        }

        Log.d(TAG, "Loaded ${currentList.size} patched apps")
        appList = currentList

        if (!silent) isRefreshing = false
    }

    private suspend fun updateLoader(appInfo: AppInfo, config: PatchConfig) {
        Log.i(TAG, "Update loader for ${appInfo.app.packageName}")
        updateLoaderState = ProcessingState.Processing
        val result = runCatching {
            withContext(Dispatchers.IO) {
                NPackageManager.apply {
                    cleanTmpApkDir()
                    cleanExternalTmpApkDir()
                }
                val apkPaths = listOf(appInfo.app.sourceDir) + (appInfo.app.splitSourceDirs ?: emptyArray())
                val patchPaths = mutableListOf<String>()
                val embeddedModulePaths = mutableListOf<String>()
                for (apk in apkPaths) {
                    ZipFile(apk).use { zip ->
                        var entry = zip.getEntry(Constants.ORIGINAL_APK_ASSET_PATH)
                        if (entry == null) entry = zip.getEntry("assets/npatch/origin_apk.bin")
                        if (entry == null) throw FileNotFoundException("Original apk entry not found for $apk")
                        zip.getInputStream(entry).use { input ->
                            val dst = lspApp.tmpApkDir.resolve(apk.substringAfterLast('/'))
                            patchPaths.add(dst.absolutePath)
                            dst.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
                ZipFile(appInfo.app.sourceDir).use { zip ->
                    zip.entries().iterator().forEach { entry ->
                        if (entry.name.startsWith(Constants.EMBEDDED_MODULES_ASSET_PATH)) {
                            val dst = lspApp.tmpApkDir.resolve(entry.name.substringAfterLast('/'))
                            embeddedModulePaths.add(dst.absolutePath)
                            zip.getInputStream(entry).use { input ->
                                dst.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
                Patcher.patch(logger, Patcher.Options(appInfo.app.packageName, false, config, patchPaths, embeddedModulePaths))
                if (!ShizukuApi.isPermissionGranted) {
                    val apkFiles = lspApp.targetApkFiles
                    if (apkFiles.isNullOrEmpty()){
                        Log.e(TAG, "No patched APK files found")
                        throw RuntimeException("No patched APK files found")
                    }
                    if (apkFiles.size > 1) {
                        val success = installApks(lspApp, apkFiles)
                    } else  {
                        installApk(lspApp, apkFiles.first())
                    }
                } else {
                    val (status, message) = NPackageManager.install()
                    if (status != PackageInstaller.STATUS_SUCCESS) throw RuntimeException(message)
                }
            }
        }
        updateLoaderState = ProcessingState.Done(result)
    }

    private suspend fun performOptimize(appInfo: AppInfo) {
        Log.i(TAG, "Perform optimize for ${appInfo.app.packageName}")
        optimizeState = ProcessingState.Processing
        val result = withContext(Dispatchers.IO) {
            ShizukuApi.performDexOptMode(appInfo.app.packageName)
        }
        optimizeState = ProcessingState.Done(result)
    }
}