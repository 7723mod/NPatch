package top.nkbe.npatch.config

import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import top.nkbe.npatch.database.LSPDatabase
import top.nkbe.npatch.database.entity.LoadedModule
import top.nkbe.npatch.database.entity.Scope
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.manager.HotReloadRegistry
import top.nkbe.npatch.manager.ModuleScopeSyncStore
import top.nkbe.npatch.util.LocalInjectedModuleService
import top.nkbe.npatch.util.ModuleLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object ConfigManager {

    private const val TAG = "ConfigManager"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val writeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val readDispatcher = Dispatchers.IO

    private val db: LSPDatabase by lazy {
        Room.databaseBuilder(
            lspApp, LSPDatabase::class.java, "modules_config.db"
        ).build()
    }


    private val moduleDao get() = db.moduleDao()
    private val scopeDao get() = db.scopeDao()

    private val loadedModules =
        ConcurrentHashMap<String, org.matrix.vector.ipc.LoadedModule>()
    private val moduleLoadLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun updateModules(newModules: Map<String, String>) {
        val changedModules =
            withContext(writeDispatcher) {
                val changed = linkedSetOf<String>()
                for (LoadedModule in moduleDao.getAll()) {
                    val apkPath = newModules[LoadedModule.pkgName]
                    if (apkPath == null) {
                        moduleDao.delete(LoadedModule)
                        removeCachedModule(LoadedModule.pkgName)
                        moduleLoadLocks.remove(LoadedModule.pkgName)
                        ModuleScopeSyncStore.deleteSnapshot(LoadedModule.pkgName)
                    } else if (LoadedModule.apkPath != apkPath) {
                        LoadedModule.apkPath = apkPath
                        moduleDao.update(LoadedModule)
                        removeCachedModule(LoadedModule.pkgName)
                        changed += LoadedModule.pkgName
                    }
                }
                for ((pkgName, apkPath) in newModules) {
                    moduleDao.insert(LoadedModule(pkgName, apkPath))
                }
                changed
            }

        for (packageName in changedModules) {
            getModuleFile(packageName)?.let(HotReloadRegistry::autoHotReload)
        }
    }

    suspend fun activateModule(pkgName: String, LoadedModule: LoadedModule) =
        withContext(writeDispatcher) {
            moduleDao.insert(LoadedModule)
            scopeDao.insert(Scope(appPkgName = pkgName, modulePkgName = LoadedModule.pkgName))
            ModuleScopeSyncStore.saveSnapshot(LoadedModule.pkgName, scopeDao.getAppsForModule(LoadedModule.pkgName))
        }

    suspend fun deactivateModule(pkgName: String, LoadedModule: LoadedModule) =
        withContext(writeDispatcher) {
            scopeDao.delete(Scope(appPkgName = pkgName, modulePkgName = LoadedModule.pkgName))
            ModuleScopeSyncStore.saveSnapshot(LoadedModule.pkgName, scopeDao.getAppsForModule(LoadedModule.pkgName))
        }

    suspend fun getModulesForApp(pkgName: String): List<LoadedModule> =
        withContext(readDispatcher) {
            return@withContext scopeDao.getModulesForApp(pkgName)
        }

    suspend fun getAppsForModule(pkgName: String): List<String> =
        withContext(readDispatcher) {
            return@withContext scopeDao.getAppsForModule(pkgName)
        }

    suspend fun getScopedModulePackageNames(): Set<String> =
        withContext(readDispatcher) {
            return@withContext scopeDao.getScopedModulePackageNames().toSet()
        }

    suspend fun clearRuntimeCache() =
        withContext(writeDispatcher) {
            loadedModules.keys.toList().forEach(::removeCachedModule)
        }

    suspend fun getModuleFilesForApp(pkgName: String): List<org.matrix.vector.ipc.LoadedModule> =
        withContext(readDispatcher) {
            val modules = scopeDao.getModulesForApp(pkgName)
            val result = ArrayList<org.matrix.vector.ipc.LoadedModule>(modules.size)
            for (LoadedModule in modules) {
                loadModule(LoadedModule, useCache = true)?.let(result::add)
            }
            return@withContext result
        }

    suspend fun getModuleFile(pkgName: String): org.matrix.vector.ipc.LoadedModule? =
        withContext(readDispatcher) {
            val LoadedModule = moduleDao.getModule(pkgName) ?: return@withContext null
            loadModule(LoadedModule, useCache = false)
        }

    suspend fun getInstalledModuleVersion(pkgName: String): Long? =
        withContext(readDispatcher) {
            moduleDao.getModule(pkgName) ?: return@withContext null
            runCatching { lspApp.packageManager.getPackageInfo(pkgName, 0).longVersionCode }
                .getOrNull()
        }

    private suspend fun loadModule(
        LoadedModule: LoadedModule,
        useCache: Boolean,
    ): org.matrix.vector.ipc.LoadedModule? {
        val mutex = moduleLoadLocks.computeIfAbsent(LoadedModule.pkgName) { Mutex() }
        mutex.lock()
        try {
            if (!File(LoadedModule.apkPath).exists()) {
                removeCachedModule(LoadedModule.pkgName)
                try {
                    LoadedModule.apkPath =
                        lspApp.packageManager.getApplicationInfo(LoadedModule.pkgName, 0).sourceDir
                    moduleDao.update(LoadedModule)
                } catch (e: PackageManager.NameNotFoundException) {
                    moduleDao.delete(LoadedModule)
                    Log.w(TAG, "LoadedModule may be uninstalled: ${LoadedModule.pkgName}")
                    return null
                }
                Log.i(TAG, "LoadedModule apk path updated: ${LoadedModule.pkgName}")
            }
            if (useCache) {
                loadedModules[LoadedModule.pkgName]?.let { return it }
            }

            val appInfo =
                runCatching {
                        lspApp.packageManager.getApplicationInfo(
                            LoadedModule.pkgName,
                            PackageManager.GET_META_DATA,
                        )
                    }
                    .getOrNull()
            val moduleCode =
                ModuleLoader.loadModule(
                    LoadedModule.apkPath,
                    readLegacyMinApiVersion(appInfo),
                ) ?: return null
            val versionCode =
                runCatching {
                        lspApp.packageManager.getPackageInfo(LoadedModule.pkgName, 0).longVersionCode
                    }
                    .getOrDefault(0L)
            val loaded =
                org.matrix.vector.ipc.LoadedModule().apply {
                    packageName = LoadedModule.pkgName
                    apkPath = LoadedModule.apkPath
                    code = moduleCode.code
                    applicationInfo = appInfo
                    appId = appInfo?.uid ?: -1
                    this.versionCode = versionCode
                    service = LocalInjectedModuleService(lspApp, LoadedModule.pkgName)
                }
            loadedModules[LoadedModule.pkgName] = loaded
            return loaded
        } finally {
            mutex.unlock()
        }
    }

    private fun removeCachedModule(packageName: String) {
        loadedModules.remove(packageName)
    }

    private fun readLegacyMinApiVersion(appInfo: android.content.pm.ApplicationInfo?): Int {
        val metadata = appInfo?.metaData ?: return 0
        if (!metadata.containsKey("xposedminversion")) {
            return 0
        }

        val intValue = metadata.getInt("xposedminversion", Int.MIN_VALUE)
        if (intValue != Int.MIN_VALUE) {
            return intValue
        }

        return metadata.getString("xposedminversion")?.trim()?.toIntOrNull() ?: 0
    }
}
