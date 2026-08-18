package top.nkbe.npatch.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException

data class DiscoveredInstaller(
    val packageName: String,
    val label: String,
)

object ThirdPartyPackageInstaller {
    private const val TAG = "ThirdPartyInstaller"
    private const val MIME_TYPE_APK = "application/vnd.android.package-archive"

    fun getDiscoveredInstallers(context: Context): List<DiscoveredInstaller> {
        val pm = context.packageManager
        val discovered = mutableMapOf<String, String>()

        val dummyUri = "content://${context.packageName}.fileprovider/dummy.apk".toUri()

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dummyUri, MIME_TYPE_APK)
        }
        val installPackageIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(dummyUri, MIME_TYPE_APK)
        }

        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }

        val activities = pm.queryIntentActivities(viewIntent, flags) +
            pm.queryIntentActivities(installPackageIntent, flags)

        for (resolveInfo in activities) {
            val pkgName = resolveInfo.activityInfo?.packageName ?: continue
            if (pkgName == context.packageName) continue
            val label = runCatching {
                resolveInfo.loadLabel(pm).toString()
            }.getOrDefault(pkgName)
            discovered[pkgName] = label
        }

        return discovered.map { DiscoveredInstaller(it.key, it.value) }
            .sortedBy { it.label }
    }

    fun isInstallerValid(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pm = context.packageManager
        val enabledFromAppInfo = runCatching {
            pm.getApplicationInfo(packageName, 0).enabled
        }.getOrNull()
        if (enabledFromAppInfo != null) return enabledFromAppInfo

        val dummyUri = "content://${context.packageName}.fileprovider/dummy.apk".toUri()
        val testIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(dummyUri, MIME_TYPE_APK)
            setPackage(packageName)
        }
        val resolved = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(testIntent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.resolveActivity(testIntent, 0)
            }
        }.getOrNull()
        return resolved != null
    }

    fun install(
        context: Context,
        apkFile: File,
        targetPackage: String? = null,
    ): Boolean {
        if (!apkFile.exists() || !apkFile.isFile) {
            throw IOException("APK file does not exist: ${apkFile.absolutePath}")
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TYPE_APK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val validTarget = targetPackage?.takeIf { isInstallerValid(context, it) }
        if (validTarget != null) {
            intent.setPackage(validTarget)
            context.grantUriPermission(validTarget, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.i(TAG, "Launching targeted third-party installer: $validTarget")
        } else if (!targetPackage.isNullOrBlank()) {
            Log.w(TAG, "Selected installer $targetPackage is not installed or invalid, falling back to system default")
        }

        val resolveList = runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }.getOrNull().orEmpty()
        for (resolveInfo in resolveList) {
            val pkg = resolveInfo.activityInfo?.packageName ?: continue
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { error ->
            Log.e(TAG, "Failed to launch installer intent", error)
            if (validTarget != null) {
                // Fallback to system chooser without setPackage
                intent.setPackage(null)
                runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
            } else {
                false
            }
        }
    }
}
