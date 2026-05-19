package nkbe.util

import android.R
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden.SessionParamsHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.appiconloader.AppIconLoader
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.share.Constants
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.*
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object NeoPackageManager {

    private const val TAG = "NeoPackageManager"
    private const val SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"

    const val STATUS_USER_CANCELLED = -2

    @Parcelize
    class AppInfo(
        val app: ApplicationInfo,
        val label: String,
        val moduleMetadata: ModuleMetadataSnapshot? = null,
    ) : Parcelable {
        val isXposedModule: Boolean
            get() = moduleMetadata != null
    }

    var appList by mutableStateOf(listOf<AppInfo>())
        private set

    @SuppressLint("StaticFieldLeak")
    private val iconLoader = AppIconLoader(lspApp.resources.getDimensionPixelSize(R.dimen.app_icon_size), false, lspApp)
    private val appIcon = Collections.synchronizedMap(mutableMapOf<String, ImageBitmap>())


    suspend fun fetchAppList() {
        val result = withContext(Dispatchers.IO) {
            val pm = lspApp.packageManager
            val collection = mutableListOf<AppInfo>()
            val applicationList: List<ApplicationInfo>

            if (ShizukuApi.isReady) {
                Log.i(TAG, "Fetching app list using Shizuku API")
                applicationList = runCatching {
                    ShizukuApi.getInstalledApplications()
                }.getOrElse { t ->
                    Log.e(TAG, "Shizuku failed to fetch app list, falling back to standard PM", t)
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                }
            } else {
                Log.i(TAG, "Fetching app list using standard PackageManager")
                applicationList = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            applicationList.forEach {
                val label = pm.getApplicationLabel(it)
                val moduleMetadata = runCatching {
                    ModuleMetadataReader.read(it, pm)
                }.getOrNull()
                collection.add(AppInfo(it, label.toString(), moduleMetadata))
            }

            collection.sortWith(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))
            val modules = buildMap {
                collection.forEach { if (it.isXposedModule) put(it.app.packageName, it.app.sourceDir) }
            }
            ConfigManager.updateModules(modules)
            collection
        }
        withContext(Dispatchers.Main.immediate) {
            appIcon.keys.retainAll(result.map { it.app.packageName }.toSet())
            appList = result
        }
    }

    fun getIcon(appInfo: AppInfo): ImageBitmap =
        appIcon[appInfo.app.packageName] ?: loadIconBitmap(appInfo.app).also {
            appIcon[appInfo.app.packageName] = it
        }

    private fun loadIconBitmap(appInfo: ApplicationInfo): ImageBitmap =
        runCatching { iconLoader.loadIcon(appInfo).asImageBitmap() }.getOrElse {
            Log.w(TAG, "Failed to load icon for ${appInfo.packageName}", it)
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

    suspend fun cleanTmpApkDir() {
        withContext(Dispatchers.IO) {
            lspApp.tmpApkDir.listFiles()?.forEach(File::delete)
        }
    }

    suspend fun cleanExternalTmpApkDir(){
        withContext(Dispatchers.IO) {
            lspApp.externalCacheDir?.listFiles()?.forEach(File::delete)
        }
    }

    suspend fun install(): Pair<Int, String?> {
        Log.i(TAG, "Perform install patched apks")
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                var flags = Refine.unsafeCast<SessionParamsHidden>(params).installFlags
                flags = flags or PackageManagerHidden.INSTALL_ALLOW_TEST or PackageManagerHidden.INSTALL_REPLACE_EXISTING
                Refine.unsafeCast<SessionParamsHidden>(params).installFlags = flags
                ShizukuApi.createPackageInstallerSession(params).use { session ->
                    val uri = Configs.storageDirectory?.toUri() ?: throw IOException("Uri is null")
                    val root = DocumentFile.fromTreeUri(lspApp, uri) ?: throw IOException("DocumentFile is null")
                    root.listFiles().forEach { file ->
                        if (file.name?.endsWith(Constants.PATCH_FILE_SUFFIX) != true) return@forEach
                        Log.d(TAG, "Add ${file.name}")
                        val input = lspApp.contentResolver.openInputStream(file.uri)
                            ?: throw IOException("Cannot open input stream")
                        input.use {
                            session.openWrite(file.name!!, 0, input.available().toLong()).use { output ->
                                input.copyTo(output)
                                session.fsync(output)
                            }
                        }
                    }
                    var result: Intent? = null
                    suspendCoroutine { cont ->
                        val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                            result = intent
                            cont.resume(Unit)
                        }
                        val intentSender = IntentSenderHelper.newIntentSender(adapter)
                        session.commit(intentSender)
                    }
                    result?.let {
                        status = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        message = it.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    } ?: throw IOException("Intent is null")
                }
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = it.message + "\n" + it.stackTraceToString()
            }
        }
        return Pair(status, message)
    }

    suspend fun uninstall(packageName: String): Pair<Int, String?> {
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                var result: Intent? = null
                suspendCoroutine { cont ->
                    val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                        result = intent
                        cont.resume(Unit)
                    }
                    val intentSender = IntentSenderHelper.newIntentSender(adapter)
                    ShizukuApi.uninstallPackage(packageName, intentSender)
                }
                result?.let {
                    status = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    message = it.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                } ?: throw IOException("Intent is null")
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = "Exception happened\n$it"
            }
        }
        return Pair(status, message)
    }

    suspend fun forceStop(packageName: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                ShizukuApi.forceStopPackage(packageName)
                true
            }.getOrDefault(false)
        }
    }

    suspend fun getAppInfoFromApks(apks: List<Uri>): Result<List<AppInfo>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                var primary: ApplicationInfo? = null
                val splits = mutableListOf<String>()
                val appInfos = mutableListOf<AppInfo>()

                apks.forEachIndexed { index, uri ->
                    val src = DocumentFile.fromSingleUri(lspApp, uri)
                        ?: throw IOException("DocumentFile is null")
                    val srcName = src.name ?: "selected-$index.apk"
                    val copiedName = if (isApksArchive(srcName)) {
                        "$index-$srcName"
                    } else {
                        sanitizeVisibleFileName(srcName)
                    }
                    val copiedFile = copyDocumentToTempFile(uri, copiedName)
                    val candidates =
                        if (isApksArchive(srcName)) extractApkArchive(copiedFile, srcName)
                        else listOf(copiedFile)

                    var uriPrimary: ApplicationInfo? = null
                    candidates.forEach { candidate ->
                        val appInfo = lspApp.packageManager.getPackageArchiveInfo(
                            candidate.absolutePath, PackageManager.GET_META_DATA
                        )?.applicationInfo
                        appInfo?.sourceDir = candidate.absolutePath
                        if (appInfo == null || uriPrimary != null) {
                            splits.add(candidate.absolutePath)
                            return@forEach
                        }
                        uriPrimary = appInfo
                        if (primary == null) primary = appInfo
                        val label = lspApp.packageManager.getApplicationLabel(appInfo).toString()
                        appInfos.add(AppInfo(appInfo, label))
                    }
                }

                primary?.splitSourceDirs = splits.toTypedArray()
                if (appInfos.isEmpty()) throw IOException("No apks")
                appInfos
            }.recoverCatching { t ->
                cleanTmpApkDir()
                Log.e(TAG, "Failed to load apks", t)
                throw t
            }
        }
    }

    private fun copyDocumentToTempFile(uri: Uri, fileName: String): File {
        val dst = uniqueTempFile(fileName)
        lspApp.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("InputStream is null")
            dst.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return dst
    }

    private fun isApksArchive(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".apks") || lower.endsWith(".xapk")
    }

    private fun extractApkArchive(archiveFile: File, archiveName: String): List<File> {
        val extracted = mutableListOf<File>()
        val prefix = sanitizeVisibleFileName(archiveName.substringBeforeLast('.', archiveName))
            .ifEmpty { "archive" }

        ZipFile(archiveFile).use { zipFile ->
            val entries = Collections.list(zipFile.entries())
                .filter { entry ->
                    !entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".apk")
                }
                .sortedWith(
                    compareBy<java.util.zip.ZipEntry> { entry ->
                        val name = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                        if (name == "base.apk") 0 else 1
                    }.thenBy { entry -> entry.name.lowercase(Locale.ROOT) }
                )
            if (entries.isEmpty()) {
                throw IOException("No APK entries found in archive: $archiveName")
            }

            entries.forEachIndexed { index, entry ->
                val entryName = entry.name.substringAfterLast('/').ifEmpty { "part-$index.apk" }
                val lowerName = entryName.lowercase(Locale.ROOT)
                val outName = when {
                    lowerName == "base.apk" -> "base_${prefix}.apk"
                    lowerName.startsWith("split_") -> "split_${prefix}_${sanitizeVisibleFileName(entryName)}"
                    else -> "split_${prefix}_${sanitizeVisibleFileName(entryName)}"
                }
                val dst = uniqueTempFile(outName)
                zipFile.getInputStream(entry).use { input ->
                    dst.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                extracted.add(dst)
            }
        }
        archiveFile.delete()
        return extracted
    }

    private fun sanitizeVisibleFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
        return cleaned.ifEmpty { "unnamed.apk" }
    }

    private fun uniqueTempFile(fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = lspApp.tmpApkDir.resolve(fileName)
        var index = 1
        while (candidate.exists()) {
            val nextName = if (ext.isEmpty()) "$baseName($index)" else "$baseName($index).$ext"
            candidate = lspApp.tmpApkDir.resolve(nextName)
            index++
        }
        return candidate
    }

    fun getLaunchIntentForPackage(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(Intent.CATEGORY_INFO)
        intentToResolve.setPackage(packageName)
        var ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) {
            intentToResolve.removeCategory(Intent.CATEGORY_INFO)
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER)
            intentToResolve.setPackage(packageName)
            ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)
        }

        if (ris.size <= 0) return null

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }

    fun getSettingsIntent(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(SETTINGS_CATEGORY)
        intentToResolve.setPackage(packageName)
        val ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) return getLaunchIntentForPackage(packageName)

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }
}
