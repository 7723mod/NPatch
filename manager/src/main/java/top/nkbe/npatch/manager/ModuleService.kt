package top.nkbe.npatch.manager

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log

import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.config.ConfigManager
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPApplicationService

class ModuleService : Service() {

    companion object {
        private const val TAG = "ModuleService"
    }

    private fun isTrustedCaller(packageName: String): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
        return packages.contains(packageName)
    }

    private fun isScopedTarget(packageName: String): Boolean {
        return runCatching {
                runBlocking { ConfigManager.getModulesForApp(packageName).isNotEmpty() }
            }
            .getOrDefault(false)
    }

    override fun onBind(intent: Intent): IBinder? {
        val packageName = intent.getStringExtra("packageName") ?: return null

        if (!isTrustedCaller(packageName) && !isScopedTarget(packageName)) {
            Log.w(TAG, "Rejected binder request from uid=${Binder.getCallingUid()} for $packageName")
            return null
        }

        Log.i(TAG, "$packageName requests binder")
        return ScopedApplicationService(packageName).asBinder()
    }

    private inner class ScopedApplicationService(private val packageName: String) :
        ILSPApplicationService.Stub() {

        private fun modules(): List<Module> {
            return runBlocking { ConfigManager.getModuleFilesForApp(packageName) }
        }

        override fun isLogMuted(): Boolean = false

        override fun getLegacyModulesList(): List<Module> {
            val list = modules().filter { it.file?.legacy == true }
            Log.d(TAG, "$packageName calls getLegacyModulesList: $list")
            return list
        }

        override fun getModulesList(): List<Module> {
            val list = modules().filter { it.file?.legacy == false }
            Log.d(TAG, "$packageName calls getModulesList: $list")
            return list
        }

        override fun getPrefsPath(packageName: String): String {
            val userId =
                runCatching { packageManager.getApplicationInfo(packageName, 0).uid / 100000 }
                    .getOrDefault(0)
            return if (userId == 0) {
                "/data/data/$packageName/shared_prefs/"
            } else {
                "/data/user/$userId/$packageName/shared_prefs/"
            }
        }

        override fun requestInjectedManagerBinder(
            binder: MutableList<IBinder>
        ): ParcelFileDescriptor? {
            Log.i(TAG, "$packageName requests injected manager binder")
            binder.add(XposedServiceBinder(packageName))
            return null
        }
    }
}
