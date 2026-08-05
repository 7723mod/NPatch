package top.nkbe.npatch.manager

import android.app.ActivityManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.lspApp
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.IFrameworkService

object ManagerService : IFrameworkService.Stub() {

    private const val TAG = "ManagerService"

    private fun getCallingPackageName(): String? {
        return lspApp.packageManager.getNameForUid(Binder.getCallingUid())
    }

    private fun getCallingProcessName(): String {
        val pid = Binder.getCallingPid()
        val activityManager = lspApp.getSystemService(ActivityManager::class.java)
        return activityManager.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?: getCallingPackageName()
            ?: "pid:$pid"
    }

    private fun recordModules(modules: List<LoadedModule>): List<LoadedModule> {
        HotReloadRegistry.recordModules(
            Binder.getCallingUid(),
            Binder.getCallingPid(),
            getCallingProcessName(),
            modules,
        )
        return modules
    }

    override fun isLogMuted(): Boolean {
        return false
    }

    override fun getLegacyModules(): List<LoadedModule> {
        val app = getCallingPackageName()
        val list = app?.let {
            runBlocking { ConfigManager.getModuleFilesForApp(it) }
        }.orEmpty().filter { it.code?.legacy == true }
        Log.d(TAG, "$app calls getLegacyModules: $list")
        return recordModules(list)
    }

    override fun getModules(): List<LoadedModule> {
        val app = getCallingPackageName()
        val list = app?.let {
            runBlocking { ConfigManager.getModuleFilesForApp(it) }
        }.orEmpty().filter { it.code?.legacy == false }
        Log.d(TAG, "$app calls getModules: $list")
        return recordModules(list)
    }

    override fun getPrefsPath(packageName: String): String {
        val userId = Binder.getCallingUid() / 100000
        return if (userId == 0) {
            "/data/data/$packageName/shared_prefs/"
        } else {
            "/data/user/$userId/$packageName/shared_prefs/"
        }
    }

    override fun openManagerApk(): ParcelFileDescriptor? {
        return null
    }

    override fun requestManagerService(): IBinder? {
        getCallingPackageName()?.let {
            Log.i(TAG, "$it requests injected manager binder from ManagerService")
            return XposedServiceBinder(it)
        }
        return null
    }

    override fun attachProcessChannel(target: IProcessChannel) {
        HotReloadRegistry.register(
            Binder.getCallingUid(),
            Binder.getCallingPid(),
            getCallingProcessName(),
            target,
        )
    }
}
