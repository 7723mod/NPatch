package top.nkbe.npatch.manager

import android.app.ActivityManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.lspApp
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.IHotReloadTarget
import org.lsposed.lspd.service.ILSPApplicationService

object ManagerService : ILSPApplicationService.Stub() {

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

    private fun recordModules(modules: List<Module>): List<Module> {
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

    override fun getLegacyModulesList(): List<Module> {
        val app = getCallingPackageName()
        val list = app?.let {
            runBlocking { ConfigManager.getModuleFilesForApp(it) }
        }.orEmpty().filter { it.file?.legacy == true }
        Log.d(TAG, "$app calls getLegacyModulesList: $list")
        return recordModules(list)
    }

    override fun getModulesList(): List<Module> {
        val app = getCallingPackageName()
        val list = app?.let {
            runBlocking { ConfigManager.getModuleFilesForApp(it) }
        }.orEmpty().filter { it.file?.legacy == false }
        Log.d(TAG, "$app calls getModulesList: $list")
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

    override fun requestInjectedManagerBinder(binder: MutableList<IBinder>): ParcelFileDescriptor? {
        getCallingPackageName()?.let {
            Log.i(TAG, "$it requests injected manager binder from ManagerService")
            binder.add(XposedServiceBinder(it))
        }
        return null
    }

    override fun registerHotReloadTarget(target: IHotReloadTarget) {
        HotReloadRegistry.register(
            Binder.getCallingUid(),
            Binder.getCallingPid(),
            getCallingProcessName(),
            target,
        )
    }
}
