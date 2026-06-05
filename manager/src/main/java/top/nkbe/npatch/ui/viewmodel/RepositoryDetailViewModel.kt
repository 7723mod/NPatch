package org.lsposed.manager.ui.compose.repository

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.repo.OnlineModule
import top.nkbe.npatch.repo.Release
import top.nkbe.npatch.repo.RepoLoader
import kotlin.collections.firstOrNull

data class InstalledState(
    val versionCode: Long,
    val versionName: String
)

class RepositoryDetailViewModel : ViewModel(), RepoLoader.RepoListener {
    private val repoLoader = RepoLoader.getInstance()

    private var targetPackageName: String? = null

    private val _module = MutableStateFlow<OnlineModule?>(null)
    val module: StateFlow<OnlineModule?> = _module

    private val _releases = MutableStateFlow<List<Release>>(emptyList())
    val releases: StateFlow<List<Release>> = _releases

    // 使用我们自己定义的 InstalledState 替代 ModuleUtil.InstalledModule
    private val _installedState = MutableStateFlow<InstalledState?>(null)
    val installedState: StateFlow<InstalledState?> = _installedState

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _errorChannel = Channel<String>()
    val errorEvents: Flow<String> = _errorChannel.receiveAsFlow()

    val isUpdateAvailable: StateFlow<Boolean> =
        combine(_module, _releases, _installedState) { online, releases, installed ->
            val pkgName = online?.name ?: return@combine false
            if (installed == null) return@combine false

            val latestVer = repoLoader.getModuleLatestVersion(pkgName)
            if (latestVer != null) return@combine latestVer.upgradable(installed.versionCode, installed.versionName)

            // 手动对比
            val latestRelease = releases.firstOrNull() ?: return@combine false

            val remoteVersion = latestRelease.tagName?.removePrefix("v")
                ?: latestRelease.name?.removePrefix("v")
                ?: return@combine false

            val installedVersion = installed.versionName.removePrefix("v")

            remoteVersion != installedVersion &&
                    !remoteVersion.contains("snapshot", true) &&
                    !remoteVersion.contains("nightly", true)

        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        repoLoader.addListener(this)
    }

    // 封装一个获取本地安装状态的辅助方法
    private fun getInstalledState(packageName: String): InstalledState? {
        val appInfo = NeoPackageManager.appList.find { it.app.packageName == packageName } ?: return null

        return InstalledState(
            versionCode = appInfo.versionCode,
            versionName = appInfo.versionName
        )
    }

    fun loadModule(packageName: String) {
        targetPackageName = packageName

        val mod = repoLoader.getOnlineModule(packageName)
        if (mod != null) {
            _module.value = mod
            _installedState.value = getInstalledState(packageName)

            loadReleases(packageName)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                repoLoader.loadRemoteData()
            }
        }
    }

    fun refresh() {
        val pkg = targetPackageName ?: return
        _isRefreshing.value = true
        loadReleases(pkg, forceRefresh = true)
    }

    private fun loadReleases(packageName: String, forceRefresh: Boolean = false) {
        val module = repoLoader.getOnlineModule(packageName)

        if (forceRefresh) {
            repoLoader.loadRemoteReleases(packageName)
            return
        }

        if (module?.releasesLoaded == true) {
            _releases.value = repoLoader.getReleases(packageName)
        } else {
            _releases.value = repoLoader.getReleases(packageName)
            repoLoader.loadRemoteReleases(packageName)
        }
    }

    override fun onModuleReleasesLoaded(module: OnlineModule?) {
        if (module != null && module.name == targetPackageName) {
            _module.value = module
            _releases.value = repoLoader.getReleases(module.name!!)
        }
        _isRefreshing.value = false
    }

    override fun onRepoLoaded() {
        targetPackageName?.let { loadModule(it) }
    }

    fun triggerLocalRefresh() {
        targetPackageName?.let {
            _installedState.value = getInstalledState(it)
        }
    }

    override fun onThrowable(t: Throwable?) {
        t?.localizedMessage?.let {
            _errorChannel.trySend(it)
        }
        _isRefreshing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        repoLoader.removeListener(this)
    }
}
