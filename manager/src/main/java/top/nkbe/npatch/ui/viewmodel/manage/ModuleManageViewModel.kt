package top.nkbe.npatch.ui.viewmodel.manage

import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nkbe.util.ModuleMetadataSnapshot
import nkbe.util.NeoPackageManager
import nkbe.util.ShizukuApi
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.manager.ModuleActivationController

class ModuleManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ModuleManageViewModel"
    }

    var isRefreshing by mutableStateOf(false)
        private set
    private var activationStateVersion by mutableStateOf(0)
    private var activationRefreshInFlight = false
    private var scopedModulePackages by mutableStateOf<Set<String>>(emptySet())

    init {
        refreshScopedActivationState()
    }

    data class ModuleInfo(
        val appInfo: NeoPackageManager.AppInfo,
        val metadata: ModuleMetadataSnapshot,
        val activationEnabled: Boolean,
    )

    val appList: List<ModuleInfo> by derivedStateOf {
        activationStateVersion
        NeoPackageManager.appList.mapNotNull { appInfo ->
            val metadata = appInfo.moduleMetadata ?: return@mapNotNull null
            val packageName = appInfo.app.packageName
            val isScoped = packageName in scopedModulePackages
            ModuleInfo(
                appInfo = appInfo,
                metadata = metadata,
                activationEnabled = isScoped,
            )
        }.sortedWith(
            compareByDescending<ModuleInfo> { it.metadata.isModern }
                .thenBy { it.metadata.isLegacy }
                .thenBy { it.appInfo.label }
        ).also {
            Log.d(TAG, "Loaded ${it.size} Xposed modules")
        }
    }

    val enabledActivationPackagesKey: String by derivedStateOf {
        scopedModulePackages.sorted().joinToString(separator = "|")
    }

    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            val scopedPackages = withContext(Dispatchers.IO) {
                NeoPackageManager.fetchAppList()
                loadActivationSnapshot()
            }
            scopedModulePackages = scopedPackages
            isRefreshing = false
            activationStateVersion++
        }
    }

    fun refreshScopedActivationState() {
        viewModelScope.launch {
            scopedModulePackages = withContext(Dispatchers.IO) {
                loadActivationSnapshot()
            }
            activationStateVersion++
        }
    }

    fun refreshEnabledActivations() {
        if (activationRefreshInFlight || !ShizukuApi.isReady) return
        val packageNames = scopedModulePackages.filter { packageName ->
            NeoPackageManager.appList.firstOrNull { it.app.packageName == packageName }
                ?.moduleMetadata
                ?.isUnsupported == false
        }
        if (packageNames.isEmpty()) return

        activationRefreshInFlight = true
        viewModelScope.launch {
            val scopedPackages = withContext(Dispatchers.IO) {
                packageNames.forEach { packageName ->
                    ModuleActivationController.activate(packageName)
                }
                loadActivationSnapshot()
            }
            scopedModulePackages = scopedPackages
            activationRefreshInFlight = false
            activationStateVersion++
        }
    }

    private suspend fun loadActivationSnapshot(): Set<String> =
        ConfigManager.getScopedModulePackageNames()
}
