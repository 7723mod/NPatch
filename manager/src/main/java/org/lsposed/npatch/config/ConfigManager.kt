package org.lsposed.npatch.config

import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.lsposed.npatch.util.LocalInjectedModuleService
import org.lsposed.npatch.database.LSPDatabase
import org.lsposed.npatch.database.entity.Module
import org.lsposed.npatch.database.entity.Scope
import org.lsposed.npatch.lspApp
import org.lsposed.npatch.util.ModuleLoader
import java.io.File

object ConfigManager {

    private const val TAG = "ConfigManager"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private val db: LSPDatabase by lazy {
        Room.databaseBuilder(
            lspApp, LSPDatabase::class.java, "modules_config.db"
        ).build()
    }


    private val moduleDao get() = db.moduleDao()
    private val scopeDao get() = db.scopeDao()

    private val loadedModules = mutableMapOf<Module, org.lsposed.lspd.models.Module>()

    suspend fun updateModules(newModules: Map<String, String>) =
        withContext(dispatcher) {
            for (module in moduleDao.getAll()) {
                val apkPath = newModules[module.pkgName]
                if (apkPath == null) {
                    moduleDao.delete(module)
                    loadedModules.remove(module)
                } else if (module.apkPath != apkPath) {
                    module.apkPath = apkPath
                    loadedModules.remove(module)
                }
            }
            for ((pkgName, apkPath) in newModules) {
                moduleDao.insert(Module(pkgName, apkPath))
            }
        }

    suspend fun activateModule(pkgName: String, module: Module) =
        withContext(dispatcher) {
            scopeDao.insert(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
        }

    suspend fun deactivateModule(pkgName: String, module: Module) =
        withContext(dispatcher) {
            scopeDao.delete(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
        }

    suspend fun getModulesForApp(pkgName: String): List<Module> =
        withContext(dispatcher) {
            return@withContext scopeDao.getModulesForApp(pkgName)
        }

    suspend fun getAppsForModule(pkgName: String): List<String> =
        withContext(dispatcher) {
            return@withContext scopeDao.getAppsForModule(pkgName)
        }

    suspend fun getModuleFilesForApp(pkgName: String): List<org.lsposed.lspd.models.Module> =
        withContext(dispatcher) {
            val modules = scopeDao.getModulesForApp(pkgName)
            return@withContext modules.mapNotNull {
                if (!File(it.apkPath).exists()) {
                    loadedModules.remove(it)
                    try {
                        it.apkPath = lspApp.packageManager.getApplicationInfo(it.pkgName, 0).sourceDir
                    } catch (e: PackageManager.NameNotFoundException) {
                        moduleDao.delete(moduleDao.getModule(it.pkgName))
                        Log.w(TAG, "Module may be uninstalled: ${it.pkgName}")
                        return@mapNotNull null
                    }
                    Log.i(TAG, "Module apk path updated: ${it.pkgName}")
                }
                loadedModules.getOrPut(it) {
                    val appInfo = runCatching {
                        lspApp.packageManager.getApplicationInfo(it.pkgName, PackageManager.GET_META_DATA)
                    }.getOrNull()
                    val preLoadedApk = ModuleLoader.loadModule(
                        it.apkPath,
                        readLegacyMinApiVersion(appInfo),
                    ) ?: return@mapNotNull null
                    org.lsposed.lspd.models.Module().apply {
                        packageName = it.pkgName
                        apkPath = it.apkPath
                        file = preLoadedApk
                        applicationInfo = appInfo
                        appId = appInfo?.uid ?: -1
                        service = LocalInjectedModuleService(lspApp, it.pkgName)
                    }
                }
            }
        }

    private fun readLegacyMinApiVersion(appInfo: android.content.pm.ApplicationInfo?): Int {
        val value = appInfo?.metaData?.get("xposedminversion") ?: return 0
        return when (value) {
            is Number -> value.toInt()
            else -> value.toString().trim().toIntOrNull() ?: 0
        }
    }
}
