package top.nkbe.npatch.ui.viewmodel

import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.repo.OnlineModule
import top.nkbe.npatch.repo.RepoLoader
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

data class RepoScopeTarget(
    val packageName: String,
    val label: String,
    val appInfo: AppInfo
)

private data class RepoFilterState(
    val modules: List<OnlineModule>,
    val query: String,
    val sort: RepoSort,
    val upgradableFirst: Boolean,
    val scopeFilter: String?
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
    private val _scopeFilter = sharedScopeFilter

    private val _upgradableFirst = MutableStateFlow(true)
    val upgradableFirst = _upgradableFirst.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    val isInitialLoading: StateFlow<Boolean> = combine(
        _isRefreshing,
        _modules
    ) { refreshing, modules ->
        (refreshing || !repoLoader.isRepoLoaded) && modules.isEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), !repoLoader.hasLocalRepo)

    val sortOrder: StateFlow<RepoSort> = _sortOrder
    val scopeFilter: StateFlow<String?> = _scopeFilter

    val availableScopeTargets: StateFlow<List<RepoScopeTarget>> = combine(
        _modules,
        _refreshTrigger,
        snapshotFlow { NeoPackageManager.appList }
    ) { modules, _, appList ->
        val scopedPackageNames = modules
            .flatMap { it.scope ?: emptyList() }
            .toSet()

        appList
            .asSequence()
            .filter { it.app.packageName in scopedPackageNames }
            .map { RepoScopeTarget(it.app.packageName, it.label, it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedScopeTarget: StateFlow<RepoScopeTarget?> = combine(
        availableScopeTargets,
        _scopeFilter
    ) { targets, selectedPackage ->
        targets.firstOrNull { it.packageName == selectedPackage }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val filterState = combine(
        _modules,
        _searchQuery,
        _sortOrder,
        _upgradableFirst,
        _scopeFilter
    ) { modules, query, sort, upgradableFirst, scopeFilter ->
        RepoFilterState(
            modules = modules,
            query = query,
            sort = sort,
            upgradableFirst = upgradableFirst,
            scopeFilter = scopeFilter
        )
    }

    val uiModels: StateFlow<List<RepoUiModel>> = combine(
        filterState,
        _refreshTrigger,
        snapshotFlow { NeoPackageManager.appList }
    ) { state, _, appList ->
        val appMap = appList.associateBy { it.app.packageName }

        val filteredByScope = if (state.scopeFilter.isNullOrEmpty()) {
            state.modules
        } else {
            state.modules.filter { module ->
                module.scope?.contains(state.scopeFilter) == true
            }
        }

        val filtered = if (state.query.isEmpty()) {
            filteredByScope
        } else {
            filteredByScope.filter {
                (it.scope?.any { scope -> scope.contains(state.query, ignoreCase = true) } == true) ||
                    (it.name?.contains(state.query, ignoreCase = true) == true) ||
                    (it.description?.contains(state.query, ignoreCase = true) == true) ||
                    (it.summary?.contains(state.query, ignoreCase = true) == true)
            }
        }

        val uiList = filtered.map { module ->
            val pkgName = module.name ?: ""

            // 使用 pre-indexed Map 提高搜尋效率 (O(1))
            val installedAppInfo = appMap[pkgName]
            val isInstalled = installedAppInfo != null

            // 获取本地安装的版本号
            val installedVersionName = installedAppInfo?.versionName
            val installedVersionCode = installedAppInfo?.versionCode ?: 0L

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
        }.sortedWith(Comparator { a, b ->
            if (state.upgradableFirst) {
                if (a.isUpgradable && !b.isUpgradable) return@Comparator -1
                if (!a.isUpgradable && b.isUpgradable) return@Comparator 1
            }

            when (state.sort) {
                RepoSort.UPDATED -> compareValues(b.module.latestReleaseTime, a.module.latestReleaseTime)
                RepoSort.CREATED -> compareValues(b.module.createdAt, a.module.createdAt)
                RepoSort.NAME -> compareValues(a.module.name, b.module.name)
                RepoSort.STARS -> compareValues(b.stargazerCount, a.stargazerCount)
            }
        })

        uiList
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun setScopeFilter(packageName: String?) {
        _scopeFilter.value = packageName
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

    companion object {
        private val sharedScopeFilter = MutableStateFlow<String?>(null)
    }
}
