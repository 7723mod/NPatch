package top.nkbe.npatch.config

import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import top.nkbe.npatch.database.LSPDatabase
import top.nkbe.npatch.database.entity.Module
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
        ConcurrentHashMap<String, org.lsposed.lspd.models.Module>()
    private val moduleLoadLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun updateModules(newModules: Map<String, String>) {
        val changedModules =
            withContext(writeDispatcher) {
                val changed = linkedSetOf<String>()
                for (module in moduleDao.getAll()) {
                    val apkPath = newModules[module.pkgName]
                    if (apkPath == null) {
                        moduleDao.delete(module)
                        removeCachedModule(module.pkgName)
                        moduleLoadLocks.remove(module.pkgName)
                        ModuleScopeSyncStore.deleteSnapshot(module.pkgName)
                    } else if (module.apkPath != apkPath) {
                        module.apkPath = apkPath
                        moduleDao.update(module)
                        removeCachedModule(module.pkgName)
                        changed += module.pkgName
                    }
                }
                for ((pkgName, apkPath) in newModules) {
                    moduleDao.insert(Module(pkgName, apkPath))
                }
                changed
            }

        for (packageName in changedModules) {
            getModuleFile(packageName)?.let(HotReloadRegistry::autoHotReload)
        }
    }

    suspend fun activateModule(pkgName: String, module: Module) =
        withContext(writeDispatcher) {
            moduleDao.insert(module)
            scopeDao.insert(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
            ModuleScopeSyncStore.saveSnapshot(module.pkgName, scopeDao.getAppsForModule(module.pkgName))
        }

    suspend fun deactivateModule(pkgName: String, module: Module) =
        withContext(writeDispatcher) {
            scopeDao.delete(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
            ModuleScopeSyncStore.saveSnapshot(module.pkgName, scopeDao.getAppsForModule(module.pkgName))
        }

    suspend fun getModulesForApp(pkgName: String): List<Module> =
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

    suspend fun getModuleFilesForApp(pkgName: String): List<org.lsposed.lspd.models.Module> =
        withContext(readDispatcher) {
            val modules = scopeDao.getModulesForApp(pkgName)
            val result = ArrayList<org.lsposed.lspd.models.Module>(modules.size)
            for (module in modules) {
                loadModule(module, useCache = true)?.let(result::add)
            }
            return@withContext result
        }

    suspend fun getModuleFile(pkgName: String): org.lsposed.lspd.models.Module? =
        withContext(readDispatcher) {
            val module = moduleDao.getModule(pkgName) ?: return@withContext null
            loadModule(module, useCache = false)
        }

    suspend fun getInstalledModuleVersion(pkgName: String): Long? =
        withContext(readDispatcher) {
            moduleDao.getModule(pkgName) ?: return@withContext null
            runCatching { lspApp.packageManager.getPackageInfo(pkgName, 0).longVersionCode }
                .getOrNull()
        }

    private suspend fun loadModule(
        module: Module,
        useCache: Boolean,
    ): org.lsposed.lspd.models.Module? {
        val mutex = moduleLoadLocks.computeIfAbsent(module.pkgName) { Mutex() }
        mutex.lock()
        try {
            if (!File(module.apkPath).exists()) {
                removeCachedModule(module.pkgName)
                try {
                    module.apkPath =
                        lspApp.packageManager.getApplicationInfo(module.pkgName, 0).sourceDir
                    moduleDao.update(module)
                } catch (e: PackageManager.NameNotFoundException) {
                    moduleDao.delete(module)
                    Log.w(TAG, "Module may be uninstalled: ${module.pkgName}")
                    return null
                }
                Log.i(TAG, "Module apk path updated: ${module.pkgName}")
            }
            if (useCache) {
                loadedModules[module.pkgName]?.let { return it }
            }

            val appInfo =
                runCatching {
                        lspApp.packageManager.getApplicationInfo(
                            module.pkgName,
                            PackageManager.GET_META_DATA,
                        )
                    }
                    .getOrNull()
            val preLoadedApk =
                ModuleLoader.loadModule(
                    module.apkPath,
                    readLegacyMinApiVersion(appInfo),
                ) ?: return null
            val versionCode =
                runCatching {
                        lspApp.packageManager.getPackageInfo(module.pkgName, 0).longVersionCode
                    }
                    .getOrDefault(0L)
            val loaded =
                org.lsposed.lspd.models.Module().apply {
                    packageName = module.pkgName
                    apkPath = module.apkPath
                    file = preLoadedApk
                    applicationInfo = appInfo
                    appId = appInfo?.uid ?: -1
                    this.versionCode = versionCode
                    service = LocalInjectedModuleService(lspApp, module.pkgName)
                }
            loadedModules[module.pkgName] = loaded
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
