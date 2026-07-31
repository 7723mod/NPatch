package top.nkbe.npatch

import android.content.Intent
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden
import android.content.pm.PackageInstallerHidden.SessionParamsHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemProperties
import dev.rikka.tools.refine.Refine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.SystemServiceHelper
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import nkbe.util.IntentSenderHelper

class ShizukuService : INPatchShizukuService.Stub() {

    private val iPackageManager: IPackageManager
        get() = IPackageManager.Stub.asInterface(SystemServiceHelper.getSystemService("package"))

    private val iPackageInstaller: IPackageInstaller
        get() = iPackageManager.packageInstaller

    init {
        runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
    }

    override fun installApks(
        apkFiles: Array<ParcelFileDescriptor>,
        names: Array<String>,
        packageName: String,
        totalSize: Long,
        userId: Int,
    ): Bundle {
        return runBlocking {
            runCatching {
                require(apkFiles.isNotEmpty()) { "No APK files provided" }
                require(apkFiles.size == names.size) { "APK file count does not match name count" }
                require(packageName.isNotBlank()) { "Package name is empty" }
                require(totalSize > 0L) { "APK set size is invalid" }
                require(names.distinct().size == names.size) { "Duplicate APK session names" }
                require(names.all { it.endsWith(".apk") && '/' !in it && '\\' !in it }) {
                    "Invalid APK session name"
                }

                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(packageName)
                    setSize(totalSize)
                }
                var flags = Refine.unsafeCast<SessionParamsHidden>(params).installFlags
                flags = flags or PackageManagerHidden.INSTALL_ALLOW_TEST or PackageManagerHidden.INSTALL_REPLACE_EXISTING
                Refine.unsafeCast<SessionParamsHidden>(params).installFlags = flags

                createSession(params, userId).use { session ->
                    apkFiles.forEachIndexed { index, descriptor ->
                        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            session.openWrite(names[index], 0, -1).use { output ->
                                input.copyTo(output, COPY_BUFFER_SIZE)
                                session.fsync(output)
                            }
                        }
                    }
                    commit(session)
                }
            }.fold(
                onSuccess = { it.toResultBundle() },
                onFailure = { failureBundle(it) },
            )
        }
    }

    override fun uninstallPackage(packageName: String, userId: Int): Bundle {
        return runBlocking {
            runCatching {
                var result: Intent? = null
                suspendCoroutine { cont ->
                    val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                        result = intent
                        cont.resume(Unit)
                    }
                    packageInstaller(userId).uninstall(packageName, IntentSenderHelper.newIntentSender(adapter))
                }
                result ?: throw IOException("Intent is null")
            }.fold(
                onSuccess = { it.toResultBundle() },
                onFailure = { failureBundle(it) },
            )
        }
    }

    override fun performDexOptMode(packageName: String, userId: Int): Boolean {
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

    override fun destroy() {
        exitProcess(0)
    }

    private fun packageInstaller(userId: Int): PackageInstaller {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", null, userId))
        } else {
            Refine.unsafeCast(PackageInstallerHidden(iPackageInstaller, "com.android.shell", userId))
        }
    }

    private fun createSession(
        params: PackageInstaller.SessionParams,
        userId: Int,
    ): PackageInstaller.Session {
        val installer = packageInstaller(userId)
        val sessionId = installer.createSession(params)
        val iSession = iPackageInstaller.openSession(sessionId)
        return Refine.unsafeCast(PackageInstallerHidden.SessionHidden(iSession))
    }

    private suspend fun commit(session: PackageInstaller.Session): Intent {
        var result: Intent? = null
        suspendCoroutine { cont ->
            val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                result = intent
                cont.resume(Unit)
            }
            session.commit(IntentSenderHelper.newIntentSender(adapter))
        }
        return result ?: throw IOException("Intent is null")
    }

    private fun Intent.toResultBundle(): Bundle {
        return Bundle().apply {
            putInt(KEY_STATUS, getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE))
            putString(KEY_MESSAGE, getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 4096 * 4096
        const val KEY_STATUS = "status"
        const val KEY_MESSAGE = "message"

        fun failureBundle(throwable: Throwable): Bundle {
            return Bundle().apply {
                putInt(KEY_STATUS, PackageInstaller.STATUS_FAILURE)
                putString(KEY_MESSAGE, throwable.message + "\n" + throwable.stackTraceToString())
            }
        }
    }
}
