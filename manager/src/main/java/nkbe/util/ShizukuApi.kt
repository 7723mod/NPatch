package nkbe.util

import android.app.IActivityManager
import android.content.ComponentName
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.*
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rikka.tools.refine.Refine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper

object ShizukuApi {
    private const val PERMISSION_REQUEST_CODE = 114514
    private var initialized = false

    private fun IBinder.wrap() = ShizukuBinderWrapper(this)
    private fun IInterface.asShizukuBinder() = this.asBinder().wrap()

    private val iPackageManager: IPackageManager
        get() = IPackageManager.Stub.asInterface(getSystemService("package"))

    private val iActivityManager: IActivityManager
        get() = IActivityManager.Stub.asInterface(getSystemService("activity"))

    private val iPackageInstaller: IPackageInstaller
        get() =
            IPackageInstaller.Stub.asInterface(iPackageManager.packageInstaller.asShizukuBinder())

    private val packageInstaller: PackageInstaller
        get() {
            val userId = Process.myUserHandle().hashCode()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Refine.unsafeCast(
                    PackageInstallerHidden(iPackageInstaller, "com.android.shell", null, userId)
                )
            } else {
                Refine.unsafeCast(
                    PackageInstallerHidden(iPackageInstaller, "com.android.shell", userId)
                )
            }
        }

    var isBinderAvailable by mutableStateOf(false)
    var isPermissionGranted by mutableStateOf(false)

    val isReady: Boolean
        get() = isBinderAvailable && isPermissionGranted

    fun init() {
        if (initialized) {
            refreshState()
            return
        }
        initialized = true
        ShizukuProvider.enableMultiProcessSupport(true)
        Shizuku.addBinderReceivedListenerSticky {
            refreshState()
        }
        Shizuku.addBinderDeadListener {
            isBinderAvailable = false
            isPermissionGranted = false
        }
    }

    fun refreshState() {
        isBinderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        isPermissionGranted =
            isBinderAvailable &&
                runCatching {
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    }
                    .getOrDefault(false)
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        refreshState()
        if (!isBinderAvailable) return
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    fun addRequestPermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removeRequestPermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun getVersionOrNull(): Int? {
        return if (isBinderAvailable) runCatching { Shizuku.getVersion() }.getOrNull() else null
    }

    fun getSystemService(name: String): IBinder {
        ensureReady()
        return SystemServiceHelper.getSystemService(name).wrap()
    }

    fun bindUserService(
        componentName: ComponentName,
        connection: ServiceConnection,
        tag: String,
        version: Int,
        daemon: Boolean = true,
    ) {
        ensureReady()
        val args =
            Shizuku.UserServiceArgs(componentName)
                .tag(tag)
                .version(version)
                .daemon(daemon)
        Shizuku.bindUserService(args, connection)
    }

    fun unbindUserService(
        componentName: ComponentName,
        connection: ServiceConnection,
        tag: String,
        version: Int,
        remove: Boolean = false,
    ) {
        val args = Shizuku.UserServiceArgs(componentName).tag(tag).version(version)
        Shizuku.unbindUserService(args, connection, remove)
    }

    private fun ensureReady() {
        refreshState()
        check(isBinderAvailable) { "Shizuku binder is not available" }
        check(isPermissionGranted) { "Shizuku permission is not granted" }
    }

    fun getInstalledApplications(): List<ApplicationInfo> {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val flags = PackageManager.GET_META_DATA.toLong()
        return iPackageManager.getInstalledApplications(flags, userId).list
    }

    fun createPackageInstallerSession(
        params: PackageInstaller.SessionParams
    ): PackageInstaller.Session {
        ensureReady()
        val sessionId = packageInstaller.createSession(params)
        val iSession =
            IPackageInstallerSession.Stub.asInterface(
                iPackageInstaller.openSession(sessionId).asShizukuBinder()
            )
        return Refine.unsafeCast(PackageInstallerHidden.SessionHidden(iSession))
    }

    fun isPackageInstalledWithoutPatch(packageName: String): Boolean {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            iPackageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA.toLong(),
                userId,
            )
        } else {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
        }
        return (app != null) && (app.metaData?.containsKey("npatch") != true)
    }

    fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        ensureReady()
        packageInstaller.uninstall(packageName, intentSender)
    }

    fun performDexOptMode(packageName: String): Boolean {
        ensureReady()
        return iPackageManager.performDexOptMode(
            packageName,
            SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false),
            "verify", true, true, null
        )
    }

    fun forceStopPackage(packageName: String) {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        iActivityManager.forceStopPackage(packageName, userId)
    }

    fun clearApplicationUserData(packageName: String, observer: IPackageDataObserver) {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val method = iPackageManager.javaClass.getMethod(
            "clearApplicationUserData",
            String::class.java,
            IPackageDataObserver::class.java,
            Int::class.java
        )
        method.invoke(iPackageManager, packageName, observer, userId)
    }

    fun setApplicationEnabledSetting(packageName: String, newState: Int) {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val method = iPackageManager.javaClass.getMethod(
            "setApplicationEnabledSetting",
            String::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java,
            String::class.java
        )
        method.invoke(iPackageManager, packageName, newState, 0, userId, "com.android.shell")
    }

    fun getApplicationEnabledSetting(packageName: String): Int {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val method = iPackageManager.javaClass.getMethod(
            "getApplicationEnabledSetting",
            String::class.java,
            Int::class.java
        )
        return method.invoke(iPackageManager, packageName, userId) as Int
    }
}
