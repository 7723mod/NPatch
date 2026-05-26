package top.nkbe.npatch.manager

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nkbe.util.NeoPackageManager
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.lspApp
import java.io.File

object ManagerCacheCleaner {

    private const val REMOTE_PREFS_PREFIX = "npatch_remote_"

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            ConfigManager.clearRuntimeCache()
            clearRemotePreferenceFiles()
            deleteRecursively(File(lspApp.filesDir, "npatch/remote"))
            deleteIfExists(File(lspApp.filesDir, "repo.json"))
            recreateDirectory(lspApp.cacheDir)
            recreateDirectory(lspApp.externalCacheDir)
            if (!lspApp.tmpApkDir.exists()) {
                lspApp.tmpApkDir.mkdirs()
            }
            NeoPackageManager.clearMemoryCache()
            NeoPackageManager.fetchAppList()
        }
    }

    private fun clearRemotePreferenceFiles() {
        val sharedPrefsDir = File(lspApp.applicationInfo.dataDir, "shared_prefs")
        val prefFiles = sharedPrefsDir.listFiles().orEmpty()
        prefFiles.forEach { file ->
            val name = file.name
            if (!name.startsWith(REMOTE_PREFS_PREFIX)) return@forEach
            val prefName = name.removeSuffix(".xml").removeSuffix(".bak")
            lspApp.deleteSharedPreferences(prefName)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) {
            deleteRecursively(file)
        }
    }

    private fun recreateDirectory(dir: File?) {
        dir ?: return
        deleteRecursively(dir)
        if (!dir.exists()) {
            dir.mkdirs()
        }
    }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach(::deleteRecursively)
        }
        file.delete()
    }
}
