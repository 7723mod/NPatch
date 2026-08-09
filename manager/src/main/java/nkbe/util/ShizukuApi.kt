package nkbe.util

import android.app.IActivityManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Intent
import android.content.pm.*
import android.content.pm.PackageInstallerHidden.SessionParamsHidden
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rikka.tools.refine.Refine
import java.util.LinkedHashSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import top.nkbe.npatch.INPatchShizukuService
import top.nkbe.npatch.ShizukuService
import top.nkbe.npatch.install.ApkInstallSet
import top.nkbe.npatch.lspApp
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper
import java.io.File

object ShizukuApi {
    private const val PERMISSION_REQUEST_CODE = 114514
    private const val USER_SERVICE_TAG = "npatch"
    private const val USER_SERVICE_VERSION = 1
    private const val USER_SERVICE_TIMEOUT_MS = 5000L
    private var initialized = false
    private val onReadyListeners = LinkedHashSet<() -> Unit>()

    @Volatile
    private var userService: INPatchShizukuService? = null
    private var userServiceDeferred = CompletableDeferred<INPatchShizukuService>()

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = INPatchShizukuService.Stub.asInterface(service)
            userService = binder
            userServiceDeferred.complete(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            userService = null
            userServiceDeferred = CompletableDeferred()
        }
    }

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
            userService = null
            userServiceDeferred = CompletableDeferred()
        }
    }

    fun refreshState() {
        val wasReady = isReady
        isBinderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        isPermissionGranted =
            isBinderAvailable &&
                runCatching {
                        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    }
                    .getOrDefault(false)
        if (!wasReady && isReady) {
            onReadyListeners.toList().forEach { listener ->
                runCatching { listener() }
            }
        }
    }

    fun addOnReadyListener(listener: () -> Unit) {
        onReadyListeners += listener
        if (isReady) {
            runCatching { listener() }
        }
    }

    fun removeOnReadyListener(listener: () -> Unit) {
        onReadyListeners -= listener
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

    private fun bindUserServiceIfNeeded() {
        if (userService != null) return
        val component = ComponentName(lspApp.packageName, ShizukuService::class.java.name)
        val args =
            Shizuku.UserServiceArgs(component)
                .tag(USER_SERVICE_TAG)
                .version(USER_SERVICE_VERSION)
                .daemon(false)
                .processNameSuffix("shizuku")
                .debuggable(false)
        Shizuku.bindUserService(args, userServiceConnection)
    }

    private suspend fun getUserService(): INPatchShizukuService {
        ensureReady()
        userService?.let { return it }
        if (userServiceDeferred.isCompleted) {
            userServiceDeferred = CompletableDeferred()
        }
        bindUserServiceIfNeeded()
        return withTimeout(USER_SERVICE_TIMEOUT_MS) { userServiceDeferred.await() }
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

    fun getInstalledPackages(flags: Int): List<PackageInfo> {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        return iPackageManager.getInstalledPackages(flags.toLong(), userId).list
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
        val app = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                iPackageManager.getApplicationInfo(
                    packageName,
                    PackageManager.GET_META_DATA.toLong(),
                    userId,
                )
            } else {
                iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
            }
        }.getOrNull()
        
        return if (app == null) {
            false // Not installed
        } else {
            app.metaData?.containsKey("npatch") != true
        }
    }

    suspend fun installApks(installSet: ApkInstallSet): Bundle {
        ensureReady()
        return runCatching {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(installSet.packageName)
                setSize(installSet.totalSize)
            }
            var flags = Refine.unsafeCast<SessionParamsHidden>(params).installFlags
            flags = flags or PackageManagerHidden.INSTALL_ALLOW_TEST or PackageManagerHidden.INSTALL_REPLACE_EXISTING
            Refine.unsafeCast<SessionParamsHidden>(params).installFlags = flags

            createPackageInstallerSession(params).use { session ->
                installSet.entries.forEach { entry ->
                    entry.file.inputStream().use { input ->
                        session.openWrite(entry.sessionName, 0L, entry.file.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
                awaitPackageInstallerResult { intentSender -> session.commit(intentSender) }
            }
        }.fold(
            onSuccess = ::resultBundle,
            onFailure = ShizukuService::failureBundle,
        )
    }

    suspend fun uninstallPackage(packageName: String): Bundle {
        ensureReady()
        return runCatching {
            awaitPackageInstallerResult { intentSender -> packageInstaller.uninstall(packageName, intentSender) }
        }.fold(
            onSuccess = ::resultBundle,
            onFailure = ShizukuService::failureBundle,
        )
    }

    private suspend fun awaitPackageInstallerResult(
        action: (android.content.IntentSender) -> Unit,
    ): Intent = suspendCoroutine { continuation ->
        val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
            continuation.resume(intent)
        }
        action(IntentSenderHelper.newIntentSender(adapter))
    }

    private fun resultBundle(intent: Intent): Bundle = Bundle().apply {
        putInt(
            ShizukuService.KEY_STATUS,
            intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
        )
        putString(ShizukuService.KEY_MESSAGE, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    fun performDexOptMode(packageName: String): Boolean {
        ensureReady()
        runCatching {
            userService?.performDexOptMode(packageName, Process.myUserHandle().hashCode())
        }.getOrNull()?.let { return it }
        val checkProfiles = SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false)
        return runCatching {
            val method = iPackageManager.javaClass.methods.firstOrNull {
                it.name == "performDexOptMode" && it.parameterTypes.contentEquals(
                    arrayOf(
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                    )
                )
            }
            if (method != null) {
                method.invoke(iPackageManager, packageName, checkProfiles, "verify", true) as Boolean
            } else {
                iPackageManager.javaClass.getMethod(
                    "performDexOptMode",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java,
                ).invoke(iPackageManager, packageName, checkProfiles, "verify", true, true, null) as Boolean
            }
        }.getOrDefault(false)
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
