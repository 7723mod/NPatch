package top.nkbe.npatch.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import top.nkbe.npatch.Patcher
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.patch.util.ManifestParser
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.share.Constants

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
    @set:JvmName("_setUseManager")
    var useManager by mutableStateOf(true)
        private set
    var newPackageName by mutableStateOf("")
    var overrideLabel by mutableStateOf("")
    var extractNativeLibs by mutableStateOf(false)
    var debuggable by mutableStateOf(false)
    var overrideVersionCode by mutableStateOf(false)
    var overrideVersionCodeValue by mutableStateOf("1")
    var overrideTargetSdk by mutableStateOf(false)
    var overrideTargetSdkValue by mutableStateOf("28")
    var sigBypassLevel by mutableStateOf(2)
    var injectProvider by mutableStateOf(false)
    var useMicroG by mutableStateOf(false)
    var outputLog by mutableStateOf(true)
    var hideLibs by mutableStateOf(false)
    var usesCleartextTraffic by mutableStateOf(false)
    var injectDex by mutableStateOf(false)
    var hasSubProcesses by mutableStateOf(false)
    var subProcessCount by mutableStateOf(0)
    var subProcesses by mutableStateOf<List<String>>(emptyList())
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
        overrideLabel = ""
        extractNativeLibs = false
        debuggable = false
        overrideVersionCode = false
        overrideVersionCodeValue = "1"
        overrideTargetSdk = false
        overrideTargetSdkValue = "28"
        sigBypassLevel = 2
        injectProvider = false
        useMicroG = false
        outputLog = true
        hideLibs = false
        usesCleartextTraffic = false
        injectDex = false
        hasSubProcesses = false
        subProcessCount = 0
        subProcesses = emptyList()
        embeddedModules = emptyList()
        logs.clear()
        hasExecutedIntent = false
    }

    fun setUseManager(value: Boolean) {
        useManager = value
        if (!value && sigBypassLevel > Constants.SIGBYPASS_EXTREME) {
            sigBypassLevel = Constants.SIGBYPASS_EXTREME
        }
    }

    private fun doneInit() {
        patchState = PatchState.SELECTING
    }

    private fun configurePatch(app: AppInfo) {
        Log.d(TAG, "Configuring patch for ${app.app.packageName}")
        patchApp = app
        patchState = PatchState.CONFIGURING
        newPackageName = app.app.packageName
        overrideLabel = ""
        extractNativeLibs = false
        try {
            val pair = ManifestParser.parseManifestFile(app.app.sourceDir)
            if (pair != null) {
                hasSubProcesses = pair.hasIsolatedOrMultiProcessComponents()
                subProcessCount = pair.getIsolatedOrMultiProcessCount()
                subProcesses = pair.getIsolatedOrMultiProcessComponents()
            } else {
                hasSubProcesses = false
                subProcessCount = 0
                subProcesses = emptyList()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to inspect manifest for subprocess components", t)
            hasSubProcesses = false
            subProcessCount = 0
            subProcesses = emptyList()
        }
    }

    private fun submitPatch() {
        Log.d(TAG, "Submit Patch")
        if (useManager) embeddedModules = emptyList()
        val patchSigBypassLevel = if (useManager) sigBypassLevel else sigBypassLevel.coerceAtMost(Constants.SIGBYPASS_HIGH)
        val patchHideLibs = hideLibs && patchSigBypassLevel > Constants.SIGBYPASS_NONE
        val patchVersionCode = overrideVersionCodeValue.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val patchTargetSdk = overrideTargetSdkValue.toIntOrNull()?.takeIf { it > 0 } ?: 28
        sigBypassLevel = patchSigBypassLevel
        hideLibs = patchHideLibs
        overrideVersionCodeValue = patchVersionCode.toString()
        overrideTargetSdkValue = patchTargetSdk.toString()
        val config = PatchConfig(
            useManager,
            debuggable,
            overrideVersionCode,
            patchVersionCode,
            patchSigBypassLevel,
            null,
            null,
            injectProvider,
            outputLog,
            newPackageName,
            useMicroG,
            patchHideLibs,
            usesCleartextTraffic,
            overrideTargetSdk,
            patchTargetSdk
        )
        patchOptions = Patcher.Options(
            newPackageName = newPackageName,
            config = config,
            apkPaths = listOf(patchApp.app.sourceDir) + (patchApp.app.splitSourceDirs ?: emptyArray()),
            embeddedModules = embeddedModules.flatMap { listOf(it.app.sourceDir) + (it.app.splitSourceDirs ?: emptyArray()) },
            targetPackageName = patchApp.app.packageName,
            embeddedModulePackages = embeddedModules.map { it.app.packageName },
            injectDex = injectDex,
            labelOverride = overrideLabel.trim().ifEmpty { null },
            extractNativeLibs = extractNativeLibs
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
        }
    }
}
