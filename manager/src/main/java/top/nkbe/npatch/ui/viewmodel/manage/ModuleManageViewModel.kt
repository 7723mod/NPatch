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

class ModuleManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ModuleManageViewModel"
    }

    var isRefreshing by mutableStateOf(false)
        private set

    data class ModuleInfo(
        val appInfo: NeoPackageManager.AppInfo,
        val metadata: ModuleMetadataSnapshot,
    )

    val appList: List<ModuleInfo> by derivedStateOf {
        NeoPackageManager.appList.mapNotNull { appInfo ->
            val metadata = appInfo.moduleMetadata ?: return@mapNotNull null
            ModuleInfo(appInfo = appInfo, metadata = metadata)
        }.sortedWith(
            compareByDescending<ModuleInfo> { it.metadata.isModern }
                .thenBy { it.metadata.isLegacy }
                .thenBy { it.appInfo.label }
        ).also {
            Log.d(TAG, "Loaded ${it.size} Xposed modules")
        }
    }

    fun refresh() {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            withContext(Dispatchers.IO) {
                NeoPackageManager.fetchAppList()
            }
            isRefreshing = false
        }
    }
}
