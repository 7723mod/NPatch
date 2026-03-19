package org.lsposed.npatch.ui.viewmodel

import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nkbe.util.NPackageManager
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.repo.OnlineModule
import org.lsposed.npatch.repo.RepoLoader
import kotlin.collections.filter
import kotlin.collections.map

data class RepoUiModel(
    val module: OnlineModule,
    val isInstalled: Boolean,
    val isUpgradable: Boolean,
    val updatableVersion: String?,
    val installedVersion: String?,
    val stargazerCount: Int
)

enum class RepoSort {
    UPDATED,
    CREATED,
    NAME,
    STARS
}

class RepositoryViewModel : ViewModel(), RepoLoader.RepoListener {
    private val repoLoader = RepoLoader.getInstance()

    private val _modules = MutableStateFlow<List<OnlineModule>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _refreshTrigger = MutableStateFlow(0)
    private val _sortOrder = MutableStateFlow(RepoSort.UPDATED)

    private val _upgradableFirst = MutableStateFlow(true)
    val upgradableFirst = _upgradableFirst.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    val sortOrder: StateFlow<RepoSort> = _sortOrder

    val uiModels: StateFlow<List<RepoUiModel>> = combine(
        _modules,
        _searchQuery,
        _sortOrder,
        _upgradableFirst,
        _refreshTrigger
    ) { modules, query, sort, upgradableFirst, _ ->

        val filtered = if (query.isEmpty()) {
            modules
        } else {
            modules.filter {
                (it.name?.contains(query, ignoreCase = true) == true) ||
                        (it.description?.contains(query, ignoreCase = true) == true) ||
                        (it.summary?.contains(query, ignoreCase = true) == true)
            }
        }

        var uiList = filtered.map { module ->
            val pkgName = module.name ?: ""

            // 使用 NPackageManager 判断是否安装
            val installedAppInfo = NPackageManager.appList.find { it.app.packageName == pkgName }
            val isInstalled = installedAppInfo != null

            // 获取本地安装的版本号
            var installedVersionName: String? = null
            var installedVersionCode = 0L
            if (isInstalled) {
                try {
                    val packageInfo = lspApp.packageManager.getPackageInfo(pkgName, 0)
                    installedVersionName = packageInfo.versionName
                    installedVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 获取线上最新版本并判断是否可更新
            val latestVersion = repoLoader.getModuleLatestVersion(pkgName)
            val isUpgradable = isInstalled && latestVersion != null && latestVersion.upgradable(
                installedVersionCode,
                installedVersionName
            )

            RepoUiModel(
                module = module,
                isInstalled = isInstalled,
                isUpgradable = isUpgradable,
                updatableVersion = latestVersion?.versionName,
                installedVersion = installedVersionName,
                stargazerCount = module.stargazerCount ?: 0
            )
        }

        // 排序逻辑保持不变
        uiList = uiList.sortedWith(Comparator { a, b ->
            if (upgradableFirst) {
                if (a.isUpgradable && !b.isUpgradable) return@Comparator -1
                if (!a.isUpgradable && b.isUpgradable) return@Comparator 1
            }

            when (sort) {
                RepoSort.UPDATED -> compareValues(b.module.latestReleaseTime, a.module.latestReleaseTime)
                RepoSort.CREATED -> compareValues(b.module.createdAt, a.module.createdAt)
                RepoSort.NAME -> compareValues(a.module.name, b.module.name)
                RepoSort.STARS -> compareValues(b.stargazerCount, a.stargazerCount)
            }
        })

        uiList
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchQuery: StateFlow<String> = _searchQuery

    init {
        repoLoader.addListener(this)
        loadModules()
    }

    private fun loadModules() {
        val list = repoLoader.onlineModules.values

        if (list.isNotEmpty()) {
            _modules.value = list.toList()
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isRefreshing.value = true
                repoLoader.loadRemoteData()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun toggleUpgradableFirst() {
        _upgradableFirst.value = !_upgradableFirst.value
    }

    fun setSortOrder(order: RepoSort) {
        _sortOrder.value = order
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    override fun onRepoLoaded() {
        loadModules()
        _isRefreshing.value = false
    }

    // 当你本地安装/卸载了模块，调用这个方法刷新仓库列表的安装状态
    fun triggerLocalRefresh() {
        _refreshTrigger.value += 1
    }

    override fun onThrowable(t: Throwable?) {
        _isRefreshing.value = false
    }

    override fun onCleared() {
        super.onCleared()
        repoLoader.removeListener(this)
    }
}
