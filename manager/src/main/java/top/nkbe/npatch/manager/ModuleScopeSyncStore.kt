package top.nkbe.npatch.manager

import android.util.Log
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.nkbe.npatch.lspApp

object ModuleScopeSyncStore {

    private const val TAG = "ModuleScopeSyncStore"
    private const val DIR_NAME = "module_scope_snapshots"

    suspend fun saveSnapshot(modulePackageName: String, appPackageNames: List<String>) =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("modulePackageName", modulePackageName)
                    put(
                        "scope",
                        JSONArray().apply {
                            appPackageNames.distinct().sorted().forEach(::put)
                        }
                    )
                }
                snapshotFile(modulePackageName).writeText(payload.toString(), Charsets.UTF_8)
            }.onFailure {
                Log.w(TAG, "Failed to save scope snapshot for $modulePackageName", it)
            }
        }

    suspend fun deleteSnapshot(modulePackageName: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = snapshotFile(modulePackageName)
                if (file.exists() && !file.delete()) {
                    Log.w(TAG, "Failed to delete scope snapshot for $modulePackageName")
                }
            }.onFailure {
                Log.w(TAG, "Failed to delete scope snapshot for $modulePackageName", it)
            }
        }

    suspend fun syncTrackedModuleScopes() =
        withContext(Dispatchers.IO) {
            if (!nkbe.util.ShizukuApi.isReady) return@withContext
            trackedModulePackages().forEach { modulePackageName ->
                ModuleActivationController.activate(modulePackageName)
            }
        }

    suspend fun syncModuleScopes(modulePackageNames: Collection<String>) =
        withContext(Dispatchers.IO) {
            if (!nkbe.util.ShizukuApi.isReady) return@withContext
            modulePackageNames.distinct().forEach { modulePackageName ->
                ModuleActivationController.activate(modulePackageName)
            }
        }

    private fun trackedModulePackages(): List<String> {
        val dir = snapshotDir()
        return dir.listFiles()
            ?.mapNotNull(::readModulePackageName)
            ?.distinct()
            .orEmpty()
    }

    private fun readModulePackageName(file: File): String? {
        return runCatching {
            JSONObject(file.readText(Charsets.UTF_8)).optString("modulePackageName")
                .takeIf { it.isNotBlank() }
        }.onFailure {
            Log.w(TAG, "Failed to read scope snapshot ${file.name}", it)
        }.getOrNull()
    }

    private fun snapshotDir(): File {
        return File(lspApp.filesDir, DIR_NAME).also { dir ->
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
    }

    private fun snapshotFile(modulePackageName: String): File {
        return File(snapshotDir(), "$modulePackageName.json")
    }
}
