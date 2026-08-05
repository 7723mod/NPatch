package top.nkbe.npatch.manager

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.config.ConfigManager
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.IFrameworkService

class ModuleService : Service() {

    companion object {
        private const val TAG = "ModuleService"
        private const val REGISTER_CLIENT_PACKAGE = 0x4E5041
    }

    private fun isScopedTarget(packageName: String): Boolean {
        return runCatching {
                runBlocking { ConfigManager.getModulesForApp(packageName).isNotEmpty() }
            }
            .getOrDefault(false)
    }

    override fun onBind(intent: Intent): IBinder? {
        val packageName = intent.getStringExtra("packageName") ?: return null

        // ActivityManager dispatches onBind, so Binder.getCallingUid() here is not the client.
        // The returned Binder validates the package on its first direct transaction instead.
        Log.i(TAG, "$packageName requests binder")
        return ScopedApplicationService(packageName).asBinder()
    }

    private inner class ScopedApplicationService(private val packageName: String) :
        IFrameworkService.Stub() {

        private val clientPackages = ConcurrentHashMap<Int, String>()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == REGISTER_CLIENT_PACKAGE) {
                data.enforceInterface("org.matrix.vector.ipc.IFrameworkService")
                val requested = data.readString()
                val uid = Binder.getCallingUid()
                val ownedPackages = packageManager.getPackagesForUid(uid).orEmpty()
                if (requested != null && requested in ownedPackages) {
                    clientPackages[Binder.getCallingPid()] = requested
                    reply?.writeNoException()
                    return true
                }
                throw SecurityException("UID $uid does not own $requested")
            }
            return super.onTransact(code, data, reply, flags)
        }

        private fun targetPackageName(): String? {
            clientPackages[Binder.getCallingPid()]?.let { return it }
            val uid = Binder.getCallingUid()
            val ownedPackages = packageManager.getPackagesForUid(uid).orEmpty()
            if (packageName in ownedPackages) return packageName
            if (ownedPackages.size > 1) {
                return ownedPackages.firstOrNull { candidate ->
                    runCatching {
                        runBlocking { ConfigManager.getModulesForApp(candidate).isNotEmpty() }
                    }.getOrDefault(false)
                }
            }
            // Isolated app ids have no PackageManager ownership mapping. Only allow the package
            // captured by the system bind when ActivityManager also associates this PID with it.
            val appId = uid % 100000
            if (appId in 99000..99999 && isScopedTarget(packageName)) {
                val process = callingProcessInfo()
                val belongsToPackage =
                    process != null &&
                        (process.pkgList?.contains(packageName) == true ||
                            process.processName == packageName ||
                            process.processName.startsWith("$packageName:"))
                if (belongsToPackage) return packageName
            }
            return null
        }

        private fun modules(): List<LoadedModule> {
            val targetPackage = targetPackageName() ?: return emptyList()
            val modules = runBlocking { ConfigManager.getModuleFilesForApp(targetPackage) }
            HotReloadRegistry.recordModules(
                Binder.getCallingUid(),
                Binder.getCallingPid(),
                callingProcessName(targetPackage),
                modules,
            )
            return modules
        }

        private fun callingProcessName(fallbackPackage: String): String {
            return callingProcessInfo()?.processName ?: fallbackPackage
        }

        private fun callingProcessInfo(): ActivityManager.RunningAppProcessInfo? {
            val pid = Binder.getCallingPid()
            return getSystemService(ActivityManager::class.java)
                .runningAppProcesses
                ?.firstOrNull { it.pid == pid }
        }

        override fun isLogMuted(): Boolean = false

        override fun getLegacyModules(): List<LoadedModule> {
            val list = modules().filter { it.code?.legacy == true }
            Log.d(TAG, "$packageName calls getLegacyModules: $list")
            return list
        }

        override fun getModules(): List<LoadedModule> {
            val list = modules().filter { it.code?.legacy == false }
            Log.d(TAG, "$packageName calls getModules: $list")
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

        override fun openManagerApk(): ParcelFileDescriptor? {
            return null
        }

        override fun requestManagerService(): IBinder? {
            val targetPackage = targetPackageName()
                ?: throw SecurityException("Unregistered ModuleService caller")
            Log.i(TAG, "$targetPackage requests injected manager binder")
            return XposedServiceBinder(targetPackage)
        }

        override fun attachProcessChannel(target: IProcessChannel) {
            val targetPackage = targetPackageName()
                ?: throw SecurityException("Unregistered ModuleService caller")
            HotReloadRegistry.register(
                Binder.getCallingUid(),
                Binder.getCallingPid(),
                callingProcessName(targetPackage),
                target,
            )
        }
    }
}
