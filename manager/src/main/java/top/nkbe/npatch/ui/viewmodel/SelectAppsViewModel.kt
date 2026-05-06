package top.nkbe.npatch.ui.viewmodel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo

class SelectAppsViewModel : ViewModel() {

    companion object {
        private const val TAG = "SelectAppViewModel"
    }

    init {
        Log.d(TAG, "SelectAppsViewModel ${toString().substringAfterLast('@')} construct")
    }

    var isRefreshing by mutableStateOf(false)
        private set

    var filteredList by mutableStateOf(listOf<AppInfo>())
        private set

    val multiSelected = mutableStateListOf<AppInfo>()

    fun filterAppList(refresh: Boolean, filter: (AppInfo) -> Boolean) {
        viewModelScope.launch {
            if (NeoPackageManager.appList.isEmpty() || refresh) {
                isRefreshing = true
                NeoPackageManager.fetchAppList()
                isRefreshing = false
            }
            filteredList = NeoPackageManager.appList.filter(filter)
            if (multiSelected.isNotEmpty()) {
                val selectedPackages = multiSelected.map { it.app.packageName }.toSet()
                multiSelected.clear()
                multiSelected.addAll(NeoPackageManager.appList.filter { it.app.packageName in selectedPackages })
            }
            Log.d(TAG, "Filtered ${filteredList.size} apps")
        }
    }
}
