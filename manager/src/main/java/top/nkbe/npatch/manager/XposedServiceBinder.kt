package top.nkbe.npatch.manager

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import androidx.core.os.BundleCompat
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.lspApp
import java.io.File
import java.io.Serializable
import java.util.HashSet

class XposedServiceBinder(private val packageName: String) : IXposedService.Stub() {

    override fun getApiVersion(): Int = IXposedService.LIB_API

    override fun getFrameworkName(): String = "NPatch"

    override fun getFrameworkVersion(): String = BuildConfig.VERSION_NAME

    override fun getFrameworkVersionCode(): Long = BuildConfig.VERSION_CODE.toLong()

    override fun getFrameworkProperties(): Long {
        return IXposedService.PROP_CAP_REMOTE
    }

    override fun getScope(): List<String> {
        return runBlocking { ConfigManager.getAppsForModule(packageName) }
    }

    override fun requestScope(packages: List<String>, callback: IXposedScopeCallback) {
        // 免 Root 下暫時自動允許，或可以加入一個彈窗確認
        runBlocking {
            packages.forEach { appPkg ->
                ConfigManager.activateModule(appPkg, top.nkbe.npatch.database.entity.Module(packageName, ""))
            }
            callback.onScopeRequestApproved(packages)
        }
    }

    override fun removeScope(packages: List<String>) {
        runBlocking {
            packages.forEach { appPkg ->
                ConfigManager.deactivateModule(appPkg, top.nkbe.npatch.database.entity.Module(packageName, ""))
            }
        }
    }

    override fun requestRemotePreferences(group: String): Bundle {
        val prefs = lspApp.getSharedPreferences(preferencesName(group), Context.MODE_PRIVATE)
        val snapshot = HashMap<String, Any>()
        prefs.all.forEach { (k, v) ->
            if (v is Serializable) {
                snapshot[k] = v
            }
        }
        return Bundle().apply {
            putSerializable("map", snapshot)
        }
    }

    override fun updateRemotePreferences(group: String, diff: Bundle) {
        val prefs = lspApp.getSharedPreferences(preferencesName(group), Context.MODE_PRIVATE)
        val editor = prefs.edit()
        
        BundleCompat.getSerializable(diff, "delete", HashSet::class.java)?.let { deletes ->
            (deletes as? Set<*>)?.forEach { key ->
                if (key is String) editor.remove(key)
            }
        }
        
        BundleCompat.getSerializable(diff, "put", HashMap::class.java)?.let { puts ->
            (puts as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String) {
                    when (v) {
                        is Boolean -> editor.putBoolean(k, v)
                        is Int -> editor.putInt(k, v)
                        is Long -> editor.putLong(k, v)
                        is Float -> editor.putFloat(k, v)
                        is String -> editor.putString(k, v)
                        is Set<*> -> {
                            val set = HashSet<String>()
                            v.forEach { if (it is String) set.add(it) }
                            editor.putStringSet(k, set)
                        }
                    }
                }
            }
        }
        editor.apply()
    }

    override fun deleteRemotePreferences(group: String) {
        lspApp.getSharedPreferences(preferencesName(group), Context.MODE_PRIVATE).edit().clear().apply()
    }

    override fun listRemoteFiles(): Array<String> {
        return remoteFilesDir().list() ?: emptyArray()
    }

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        if (!isSafeRelativePath(name)) throw RemoteException("Invalid file name")
        val file = File(remoteFilesDir(), name)
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
    }

    override fun deleteRemoteFile(name: String): Boolean {
        if (!isSafeRelativePath(name)) return false
        return File(remoteFilesDir(), name).delete()
    }

    private fun preferencesName(group: String): String {
        return "npatch_remote_${safeName(packageName)}_${safeName(group)}"
    }

    private fun remoteFilesDir(): File {
        return File(lspApp.filesDir, "npatch/remote/${safeName(packageName)}")
    }

    private fun safeName(name: String): String {
        return name.replace("[^A-Za-z0-9_.-]".toRegex(), "_")
    }

    private fun isSafeRelativePath(path: String): Boolean {
        return path.isNotEmpty() && path != "." && path != ".." && !path.contains("/") && !path.contains("\\")
    }
}
