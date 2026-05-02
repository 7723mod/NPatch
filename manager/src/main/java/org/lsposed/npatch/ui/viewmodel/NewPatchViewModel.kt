package org.lsposed.npatch.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.lsposed.npatch.Patcher
import org.lsposed.npatch.share.PatchConfig
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import org.lsposed.npatch.patch.util.Logger

class NewPatchViewModel : ViewModel() {

    companion object {
        private const val TAG = "NewPatchViewModel"
    }

    enum class PatchState {
        INIT, SELECTING, CONFIGURING, PATCHING, FINISHED, ERROR
    }

    enum class InstallMethod {
        SYSTEM, SHIZUKU
    }

    sealed class ViewAction {
        object DoneInit : ViewAction()
        data class ConfigurePatch(val app: AppInfo) : ViewAction()
        object SubmitPatch : ViewAction()
        object LaunchPatch : ViewAction()
    }

    var patchState by mutableStateOf(PatchState.INIT)
        private set

    // Patch Configuration
    var useManager by mutableStateOf(true)
    var newPackageName by mutableStateOf("")
    var debuggable by mutableStateOf(false)
    var overrideVersionCode by mutableStateOf(false)
    var sigBypassLevel by mutableStateOf(2)
    var injectDex by mutableStateOf(false)
    var injectProvider by mutableStateOf(false)
    var useMicroG by mutableStateOf(false)
    var outputLog by mutableStateOf(true)
    var embeddedModules by mutableStateOf<List<AppInfo>>(emptyList())
    var hasExecutedIntent by mutableStateOf(false)

    lateinit var patchApp: AppInfo
        private set
    lateinit var patchOptions: Patcher.Options
        private set

    val logs = mutableStateListOf<Pair<Int, String>>()
    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) {
                Log.d(TAG, msg)
                logs += Log.DEBUG to msg
            }
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
            logs += Log.INFO to msg
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
            logs += Log.ERROR to msg
        }
    }

    fun dispatch(action: ViewAction) {
        viewModelScope.launch {
            when (action) {
                is ViewAction.DoneInit -> doneInit()
                is ViewAction.ConfigurePatch -> configurePatch(action.app)
                is ViewAction.SubmitPatch -> submitPatch()
                is ViewAction.LaunchPatch -> launchPatch()
            }
        }
    }

    fun reset() {
        patchState = PatchState.INIT
        useManager = true
        newPackageName = ""
        debuggable = false
        overrideVersionCode = false
        sigBypassLevel = 2
        injectDex = false
        injectProvider = false
        useMicroG = false
        outputLog = true
        embeddedModules = emptyList()
        logs.clear()
        hasExecutedIntent = false
    }
    private fun doneInit() {
        patchState = PatchState.SELECTING
    }

    private fun configurePatch(app: AppInfo) {
        Log.d(TAG, "Configuring patch for ${app.app.packageName}")
        patchApp = app
        patchState = PatchState.CONFIGURING
        newPackageName = app.app.packageName
    }

    private fun submitPatch() {
        Log.d(TAG, "Submit Patch")
        if (useManager) embeddedModules = emptyList()
        val config = PatchConfig(useManager, debuggable, overrideVersionCode, sigBypassLevel, null, null, injectProvider, outputLog, newPackageName, useMicroG)
        patchOptions = Patcher.Options(
            newPackageName = newPackageName,
            injectDex = injectDex,
            config = config,
            apkPaths = listOf(patchApp.app.sourceDir) + (patchApp.app.splitSourceDirs ?: emptyArray()),
            embeddedModules = embeddedModules.flatMap { listOf(it.app.sourceDir) + (it.app.splitSourceDirs ?: emptyArray()) }
        )
        patchState = PatchState.PATCHING
    }

    private suspend fun launchPatch() {
        logger.i("Launch Patch")
        patchState = try {
            Patcher.patch(logger, patchOptions)
            PatchState.FINISHED
        } catch (t: Throwable) {
            logger.e(t.message.orEmpty())
            logger.e(t.stackTraceToString())
            PatchState.ERROR
        } finally {
            NeoPackageManager.cleanTmpApkDir()
        }
    }
}