package top.nkbe.npatch.manager

import android.app.ActivityThread
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import top.nkbe.npatch.BuildConfig
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

object ManagerIntegrity {
    private const val TAG = "NPatch-Integrity"
    private const val SYSTEM_PACKAGE_INFO_CREATOR = "android.content.pm.PackageInfo\$1"
    private const val SYSTEM_PM_PROXY = "android.content.pm.IPackageManager\$Stub\$Proxy"
    private const val SIGNATURE_KEY = 0x5A

    fun verifyOnStartup(context: Context) {
        val report = runChecks(context)
        report.warnings.forEach { Log.w(TAG, it) }
        if (!report.signatureTrusted) {
            Log.e(TAG, "Manager signature verification failed: ${report.signatures.joinToString()}")
            if (!BuildConfig.DEBUG) {
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun runChecks(context: Context): IntegrityReport {
        val warnings = mutableListOf<String>()
        val signatures = currentSignatureDigests(context)
        val expected = BuildConfig.MANAGER_SIGNATURE_SHA256_ALLOWLIST
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(::decodeAllowlistEntry)
            .toSet()
        val signatureTrusted = expected.isEmpty() || signatures.any { digest -> expected.any { it.contentEquals(digest) } }
        if (!signatureTrusted) {
            warnings += "Unexpected manager signing certificate: ${signatures.joinToString { it.toHexString() }}"
        }

        runCatching {
            val creator = PackageInfo.CREATOR
            val creatorClass = creator.javaClass
            if (creatorClass.name != SYSTEM_PACKAGE_INFO_CREATOR || creatorClass.declaredFields.isNotEmpty()) {
                warnings += "PackageInfo.CREATOR looks replaced: ${creatorClass.name}"
            }
        }.onFailure {
            warnings += "PackageInfo.CREATOR check failed: ${it.javaClass.simpleName}"
        }

        runCatching {
            val pmField = context.packageManager.javaClass.getDeclaredField("mPM")
            pmField.isAccessible = true
            val pm = pmField.get(context.packageManager)
            if (pm != null && pm.javaClass.name != SYSTEM_PM_PROXY) {
                warnings += "PackageManager.mPM looks proxied: ${pm.javaClass.name}"
            }
        }.onFailure {
            warnings += "PackageManager.mPM check skipped: ${it.javaClass.simpleName}"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val appComponentFactory = context.applicationInfo.appComponentFactory
                if (!appComponentFactory.isNullOrBlank() && appComponentFactory != BuildConfig.APPLICATION_ID) {
                    Log.d(TAG, "Manager AppComponentFactory: $appComponentFactory")
                }
            }
        }

        runCatching {
            val activityThread = ActivityThread.currentActivityThread()
            val initialApplication = activityThread?.let {
                val field = it.javaClass.getDeclaredField("mInitialApplication")
                field.isAccessible = true
                field.get(it)
            }
            if (initialApplication != null && initialApplication.javaClass.name != context.applicationContext.javaClass.name) {
                warnings += "Initial application mismatch: ${initialApplication.javaClass.name}"
            }
        }.onFailure {
            warnings += "ActivityThread application check skipped: ${it.javaClass.simpleName}"
        }

        return IntegrityReport(signatureTrusted, signatures, warnings)
    }

    @Suppress("DEPRECATION")
    private fun currentSignatureDigests(context: Context): List<ByteArray> {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            packageInfo.signatures
        }.orEmpty()
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        }
    }

    private fun decodeAllowlistEntry(value: String): ByteArray {
        return Base64.getDecoder()
            .decode(value)
            .map { byte -> (byte.toInt() xor SIGNATURE_KEY).toByte() }
            .toByteArray()
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }

    private data class IntegrityReport(
        val signatureTrusted: Boolean,
        val signatures: List<ByteArray>,
        val warnings: List<String>,
    )
}
