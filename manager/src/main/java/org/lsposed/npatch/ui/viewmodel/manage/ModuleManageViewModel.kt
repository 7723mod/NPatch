package org.lsposed.npatch.ui.viewmodel.manage

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
import nkbe.util.NeoPackageManager

class ModuleManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ModuleManageViewModel"
    }

    var isRefreshing by mutableStateOf(false)
        private set

    class XposedInfo(
        val api: Int,
        val description: String,
        val scope: List<String>
    )

    val appList: List<Pair<NeoPackageManager.AppInfo, XposedInfo>> by derivedStateOf {
        NeoPackageManager.appList.mapNotNull { appInfo ->
            val metaData = appInfo.app.metaData ?: return@mapNotNull null
            appInfo to XposedInfo(
                metaData.getInt("xposedminversion", -1).also { if (it == -1) return@mapNotNull null },
                metaData.getString("xposeddescription") ?: "",
                emptyList() // TODO: scope
            )
        }.also {
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
