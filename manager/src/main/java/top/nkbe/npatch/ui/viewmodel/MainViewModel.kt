package top.nkbe.npatch.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import top.nkbe.npatch.config.ThemeConfig

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val background: StateFlow<String> = ThemeConfig.getThemeFlow(getApplication())
        .map { it.backgroundImageUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
}
