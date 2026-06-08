package nkbe.util

import android.R
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.appiconloader.AppIconLoader
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.ShizukuService
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.share.Constants
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.*
import java.util.Collections
import java.util.zip.ZipFile

object NeoPackageManager {

    private const val TAG = "NeoPackageManager"
    private const val SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"

    const val STATUS_USER_CANCELLED = -2

    private val appScanDispatcher by lazy {
        Dispatchers.IO.limitedParallelism(maxOf(2, minOf(Runtime.getRuntime().availableProcessors(), 8)))
    }

    @Parcelize
    class AppInfo(
        val app: ApplicationInfo,
        val label: String,
        val versionName: String,
        val versionCode: Long,
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
            val packages: List<android.content.pm.PackageInfo>

            if (ShizukuApi.isReady) {
                Log.i(TAG, "Fetching app list using Shizuku API")
                packages = runCatching {
                    ShizukuApi.getInstalledPackages(PackageManager.GET_META_DATA)
                }.getOrElse { t ->
                    Log.e(TAG, "Shizuku failed to fetch package list, falling back to standard PM", t)
                    pm.getInstalledPackages(PackageManager.GET_META_DATA)
                }
            } else {
                Log.i(TAG, "Fetching app list using standard PackageManager")
                packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            }

            val collection = coroutineScope {
                packages.map { pkgInfo ->
                    async(appScanDispatcher) {
                        val appInfo = pkgInfo.applicationInfo ?: return@async null
                        val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                            .getOrElse { throwable ->
                                Log.w(TAG, "Failed to load label for ${appInfo.packageName}", throwable)
                                appInfo.packageName
                            }
                        val moduleMetadata = runCatching {
                            ModuleMetadataReader.read(pkgInfo, pm)
                        }.getOrNull()
                        AppInfo(
                            app = appInfo,
                            label = label,
                            versionName = pkgInfo.versionName ?: "",
                            versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo),
                            moduleMetadata = moduleMetadata
                        )
                    }
                }.awaitAll().filterNotNull().toMutableList()
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

    fun clearMemoryCache() {
        appIcon.clear()
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
                val result = ShizukuApi.installApks(collectInstallApkFiles())
                status = result.getInt(ShizukuService.KEY_STATUS, PackageInstaller.STATUS_FAILURE)
                message = result.getString(ShizukuService.KEY_MESSAGE)
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
                val result = ShizukuApi.uninstallPackage(packageName)
                status = result.getInt(ShizukuService.KEY_STATUS, PackageInstaller.STATUS_FAILURE)
                message = result.getString(ShizukuService.KEY_MESSAGE)
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = "Exception happened\n$it"
            }
        }
        return Pair(status, message)
    }

    private fun collectInstallApkFiles(): List<File> {
        val localApkFiles = lspApp.targetApkFiles
            ?.filter { it.isFile && it.name.endsWith(Constants.PATCH_FILE_SUFFIX) }
            .orEmpty()
        if (localApkFiles.isNotEmpty()) {
            return localApkFiles
        }

        val uri = Configs.storageDirectory?.toUri() ?: throw IOException("Uri is null")
        val root = DocumentFile.fromTreeUri(lspApp, uri) ?: throw IOException("DocumentFile is null")
        val apkFiles = mutableListOf<File>()
        root.listFiles().forEach { file ->
            val fileName = file.name ?: return@forEach
            when {
                fileName.endsWith(Constants.PATCH_FILE_SUFFIX) -> {
                    apkFiles.add(copyDocumentToTempFile(file.uri, sanitizeVisibleFileName(fileName)))
                }

                fileName.endsWith(Constants.PATCH_ARCHIVE_SUFFIX) -> {
                    val copiedArchive = copyDocumentToTempFile(file.uri, sanitizeVisibleFileName(fileName))
                    apkFiles.addAll(extractApkArchive(copiedArchive, fileName))
                }
            }
        }
        if (apkFiles.isEmpty()) throw IOException("No target APK files found for installation")
        return apkFiles
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
                        val pkgInfo = lspApp.packageManager.getPackageArchiveInfo(
                            candidate.absolutePath, PackageManager.GET_META_DATA
                        )
                        val appInfo = pkgInfo?.applicationInfo
                        appInfo?.sourceDir = candidate.absolutePath
                        if (appInfo == null || uriPrimary != null) {
                            splits.add(candidate.absolutePath)
                            return@forEach
                        }
                        uriPrimary = appInfo
                        if (primary == null) primary = appInfo
                        val label = lspApp.packageManager.getApplicationLabel(appInfo).toString()
                        appInfos.add(
                            AppInfo(
                                app = appInfo,
                                label = label,
                                versionName = pkgInfo.versionName ?: "",
                                versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo),
                                moduleMetadata = ModuleMetadataReader.read(pkgInfo, lspApp.packageManager)
                            )
                        )
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
            val entries = selectInstallableApkEntries(Collections.list(zipFile.entries()))
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

    private fun selectInstallableApkEntries(entries: List<java.util.zip.ZipEntry>): List<java.util.zip.ZipEntry> {
        val apkEntries = entries
            .filter { entry -> !entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".apk") }
            .sortedBy { entry -> normalizeZipPath(entry.name) }
        if (apkEntries.isEmpty()) return emptyList()

        val baseEntry = apkEntries
            .filter { entry -> isBaseApkName(entry.name.substringAfterLast('/')) }
            .minWithOrNull(compareBy<java.util.zip.ZipEntry> { baseEntryPriority(it.name) }.thenBy { normalizeZipPath(it.name) })
        if (baseEntry != null) {
            val baseDir = zipParent(baseEntry.name)
            return apkEntries
                .filter { entry ->
                    zipParent(entry.name) == baseDir &&
                        !entry.name.substringAfterLast('/').lowercase(Locale.ROOT).startsWith("standalone")
                }
                .sortedWith(compareBy<java.util.zip.ZipEntry> { baseEntryPriority(it.name) }.thenBy { normalizeZipPath(it.name) })
        }

        val standaloneEntries = apkEntries.filter { entry ->
            entry.name.substringAfterLast('/').lowercase(Locale.ROOT).startsWith("standalone")
        }
        if (standaloneEntries.isNotEmpty()) {
            return listOf(
                standaloneEntries.minWithOrNull(
                    compareBy<java.util.zip.ZipEntry> { standaloneEntryPriority(it.name) }
                        .thenBy { normalizeZipPath(it.name) }
                )!!
            )
        }

        return apkEntries
            .groupBy { entry -> zipParent(entry.name) }
            .values
            .maxWithOrNull(
                compareBy<List<java.util.zip.ZipEntry>> { it.size }
                    .thenByDescending { group -> group.count { entry -> entry.name.substringAfterLast('/').contains("config.", ignoreCase = true) } }
                    .thenBy { group -> group.minOf { entry -> normalizeZipPath(entry.name).length } }
            )
            ?.sortedBy { entry -> normalizeZipPath(entry.name) }
            ?: emptyList()
    }

    private fun normalizeZipPath(path: String): String = path.replace('\\', '/').lowercase(Locale.ROOT)

    private fun zipParent(path: String): String = normalizeZipPath(path).substringBeforeLast('/', "")

    private fun isBaseApkName(name: String): Boolean {
        val lowerName = name.lowercase(Locale.ROOT)
        return lowerName == "base.apk" || (lowerName.startsWith("base-") && lowerName.endsWith(".apk"))
    }

    private fun baseEntryPriority(path: String): Int {
        val lowerName = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            lowerName == "base.apk" -> 0
            lowerName == "base-master.apk" -> 1
            lowerName.startsWith("base-") -> 2
            else -> 3
        }
    }

    private fun standaloneEntryPriority(path: String): Int {
        val lowerName = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            "universal" in lowerName -> 0
            "master" in lowerName -> 1
            else -> 2
        }
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
