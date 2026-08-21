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
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.appiconloader.AppIconLoader
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.ShizukuService
import top.nkbe.npatch.install.ApkInstallSet
import top.nkbe.npatch.install.SystemInstallResult
import top.nkbe.npatch.install.SystemPackageInstaller
import top.nkbe.npatch.lspApp
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.*
import java.util.Collections
import java.util.zip.ZipFile

object NeoPackageManager {

    private const val TAG = "NeoPackageManager"
    private const val SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"
    private const val MAX_ARCHIVE_APK_COUNT = 256
    private const val MAX_ARCHIVE_EXTRACTED_BYTES = 4L * 1024 * 1024 * 1024

    const val STATUS_USER_CANCELLED = -2

    enum class InstallMethod {
        SYSTEM,
        SHIZUKU,
    }

    enum class PatchedType(val displayName: String) {
        NONE(""),
        NPATCH("NPatch"),
        LSPATCH("LSPatch"),
        FPA("FPA"),
        EMBEDDED("Embedded APK");
    }

    sealed interface ExtractResult {
        data class Success(val originalAppInfo: AppInfo) : ExtractResult
        data object NoOriginalApk : ExtractResult
        data class PackageMismatch(val outerPkg: String, val innerPkg: String, val originalAppInfo: AppInfo) : ExtractResult
        data class Corrupted(val message: String, val cause: Throwable?) : ExtractResult
        data class Error(val message: String, val cause: Throwable?) : ExtractResult
    }

    sealed interface InstallOutcome {
        data class Completed(val status: Int, val message: String?) : InstallOutcome
        data object PermissionRequired : InstallOutcome
    }

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

        // Fast in-memory Tier 1 & 2 detection (for zero-IO UI rendering)
        val patchedType: PatchedType
            get() {
                val meta = app.metaData
                val factory = app.appComponentFactory.orEmpty()
                val className = app.className.orEmpty()

                // Tier 1: Manifest Meta-Data (Outermost explicit marker)
                if (meta?.containsKey("npatch") == true) return PatchedType.NPATCH
                if (meta?.containsKey("lspatch") == true) return PatchedType.LSPATCH
                if (meta?.containsKey("fpa") == true) return PatchedType.FPA

                // Tier 2: AppComponentFactory & Application class name
                if (factory.contains("top.nkbe.npatch") || className.contains("top.nkbe.npatch")) return PatchedType.NPATCH
                if (factory.contains("org.lsposed.lspatch") || className.contains("org.lsposed.lspatch")) return PatchedType.LSPATCH
                if (factory.startsWith("fpa.") || className.startsWith("fpa.") || factory.contains("fun.fpa") || className.contains("fun.fpa")) return PatchedType.FPA

                return PatchedType.NONE
            }

        val isPatched: Boolean
            get() = patchedType != PatchedType.NONE
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

    suspend fun install(method: InstallMethod): InstallOutcome {
        Log.i(TAG, "Installing patched APK set with $method")
        return withContext(Dispatchers.IO) {
            val installFiles = collectInstallApkFiles()
            val installSet = ApkInstallSet.fromFiles(lspApp, installFiles)
            val targetPkg = installSet.packageName
            val thirdPartyPkg = top.nkbe.npatch.config.Configs.thirdPartyInstallerPackage

            val outcome = runCatching {
                if (thirdPartyPkg.isNotBlank() && top.nkbe.npatch.install.ThirdPartyPackageInstaller.isInstallerValid(lspApp, thirdPartyPkg)) {
                    val launched = top.nkbe.npatch.install.ThirdPartyPackageInstaller.install(
                        lspApp,
                        installSet.entries.first().file,
                        thirdPartyPkg,
                    )
                    if (launched) {
                        InstallOutcome.Completed(PackageInstaller.STATUS_SUCCESS, "Handoff to $thirdPartyPkg")
                    } else {
                        performInternalInstall(method, installSet)
                    }
                } else {
                    performInternalInstall(method, installSet)
                }
            }.getOrElse { error ->
                InstallOutcome.Completed(
                    PackageInstaller.STATUS_FAILURE,
                    error.message + "\n" + error.stackTraceToString(),
                )
            }

            if (outcome is InstallOutcome.Completed && (outcome.status == PackageInstaller.STATUS_SUCCESS || outcome.status == PackageInstaller.STATUS_PENDING_USER_ACTION)) {
                runCatching {
                    top.nkbe.npatch.install.InstallNotificationHelper.notifyInstallSuccess(lspApp, targetPkg)
                }.onFailure {
                    Log.e(TAG, "Failed to send install notification for $targetPkg", it)
                }
            }

            outcome
        }
    }

    private suspend fun performInternalInstall(method: InstallMethod, installSet: ApkInstallSet): InstallOutcome {
        return when (method) {
            InstallMethod.SHIZUKU -> {
                val result = ShizukuApi.installApks(installSet)
                InstallOutcome.Completed(
                    result.getInt(
                        ShizukuService.KEY_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    ),
                    result.getString(ShizukuService.KEY_MESSAGE),
                )
            }

            InstallMethod.SYSTEM -> fallbackSystemInstall(installSet)
        }
    }

    private suspend fun fallbackSystemInstall(installSet: ApkInstallSet): InstallOutcome {
        return when (val result = SystemPackageInstaller.install(lspApp, installSet)) {
            is SystemInstallResult.Completed -> InstallOutcome.Completed(
                result.status,
                result.message,
            )

            SystemInstallResult.PermissionRequired -> InstallOutcome.PermissionRequired
        }
    }

    suspend fun uninstall(packageName: String): Pair<Int, String?> {
        if (!ShizukuApi.isReady) {
            return Pair(PackageInstaller.STATUS_FAILURE, "Shizuku not ready")
        }
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
        val apkFiles = lspApp.targetApkFiles.orEmpty().toList()
        if (apkFiles.isEmpty()) throw IOException("No active patched APK set")
        if (apkFiles.any { !it.isFile }) throw IOException("Patched APK set is no longer available")
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
                val candidates = mutableListOf<File>()

                apks.forEachIndexed { index, uri ->
                    val src = DocumentFile.fromSingleUri(lspApp, uri)
                        ?: throw IOException("DocumentFile is null")
                    val srcName = src.name ?: "selected-$index.apk"
                    val copiedName = if (isApksArchiveName(srcName)) {
                        "$index-$srcName"
                    } else {
                        sanitizeVisibleFileName(srcName)
                    }
                    val copiedFile = copyDocumentToTempFile(uri, copiedName)
                    val isArchive = isApksArchive(srcName, copiedFile)
                    val selectedFiles =
                        if (isArchive) extractApkArchive(copiedFile, srcName)
                        else listOf(copiedFile)
                    candidates += selectedFiles
                }

                val installSet = ApkInstallSet.fromFiles(lspApp, candidates)
                val baseFile = installSet.entries.first().file
                val pkgInfo = lspApp.packageManager.getPackageArchiveInfo(
                    baseFile.absolutePath,
                    PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES,
                ) ?: throw IOException("Unable to parse base APK: ${baseFile.name}")
                val appInfo = pkgInfo.applicationInfo
                    ?: throw IOException("Base APK has no application info: ${baseFile.name}")
                appInfo.sourceDir = baseFile.absolutePath
                appInfo.publicSourceDir = baseFile.absolutePath
                appInfo.splitSourceDirs = installSet.entries
                    .drop(1)
                    .map { it.file.absolutePath }
                    .toTypedArray()
                val label = runCatching {
                    lspApp.packageManager.getApplicationLabel(appInfo).toString()
                }.getOrNull().takeUnless { it.isNullOrBlank() } ?: appInfo.packageName
                listOf(
                    AppInfo(
                        app = appInfo,
                        label = label,
                        versionName = pkgInfo.versionName ?: "",
                        versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo),
                        moduleMetadata = ModuleMetadataReader.read(pkgInfo, lspApp.packageManager),
                    )
                )
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

    private fun isStandaloneApk(file: File): Boolean {
        return runCatching {
            ZipFile(file).use { zip ->
                zip.getEntry("AndroidManifest.xml") != null
            }
        }.getOrDefault(false)
    }

    private fun isApksArchiveName(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm") || lower.endsWith(".zip")
    }

    private fun isApksArchive(fileName: String, file: File? = null): Boolean {
        if (file != null && isStandaloneApk(file)) {
            return false
        }
        return isApksArchiveName(fileName)
    }

    private fun extractApkArchive(archiveFile: File, archiveName: String): List<File> {
        val extracted = mutableListOf<File>()
        val prefix = sanitizeVisibleFileName(archiveName.substringBeforeLast('.', archiveName))
            .ifEmpty { "archive" }
        var extractedBytes = 0L

        try {
            ZipFile(archiveFile).use { zipFile ->
                val entries = selectInstallableApkEntries(Collections.list(zipFile.entries()))
                if (entries.isEmpty()) {
                    throw IOException("No APK entries found in archive: $archiveName")
                }
                if (entries.size > MAX_ARCHIVE_APK_COUNT) {
                    throw IOException("Too many APK entries in archive: ${entries.size}")
                }

                val duplicatePath = entries
                    .groupingBy { normalizeZipPath(it.name) }
                    .eachCount()
                    .entries
                    .firstOrNull { it.value > 1 }
                if (duplicatePath != null) {
                    throw IOException("Duplicate APK entry in archive: ${duplicatePath.key}")
                }

                entries.forEachIndexed { index, entry ->
                    val entryName = entry.name.substringAfterLast('/').ifEmpty { "part-$index.apk" }
                    val lowerName = entryName.lowercase(Locale.ROOT)
                    val outName = when {
                        lowerName == "base.apk" -> "base_${prefix}.apk"
                        else -> "split_${prefix}_${sanitizeVisibleFileName(entryName)}"
                    }
                    val dst = uniqueTempFile(outName)
                    val remaining = MAX_ARCHIVE_EXTRACTED_BYTES - extractedBytes
                    extractedBytes += copyArchiveEntry(zipFile, entry, dst, remaining)
                    extracted.add(dst)
                }
            }
            return extracted
        } catch (error: Throwable) {
            extracted.forEach(File::delete)
            throw error
        } finally {
            archiveFile.delete()
        }
    }

    private fun copyArchiveEntry(
        zipFile: ZipFile,
        entry: java.util.zip.ZipEntry,
        destination: File,
        remainingBytes: Long,
    ): Long {
        if (remainingBytes <= 0L || entry.size > remainingBytes) {
            throw IOException("APK archive exceeds extraction size limit")
        }
        var written = 0L
        try {
            zipFile.getInputStream(entry).use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > remainingBytes) {
                            throw IOException("APK archive exceeds extraction size limit")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            return written
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun selectInstallableApkEntries(entries: List<java.util.zip.ZipEntry>): List<java.util.zip.ZipEntry> {
        val apkEntries = entries
            .filter { entry ->
                if (entry.isDirectory || !entry.name.lowercase(Locale.ROOT).endsWith(".apk")) return@filter false
                val normalized = normalizeZipPath(entry.name)
                // Filter out embedded APKs that might be in assets, res, lib, META-INF
                !normalized.startsWith("assets/") &&
                !normalized.startsWith("res/") &&
                !normalized.startsWith("lib/") &&
                !normalized.startsWith("meta-inf/")
            }
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

    fun detectPatchedTypeDeep(appInfo: AppInfo): PatchedType {
        val fastType = appInfo.patchedType
        if (fastType != PatchedType.NONE) return fastType

        val sourceDir = appInfo.app.sourceDir ?: return PatchedType.NONE
        val sourceFile = File(sourceDir)
        if (!sourceFile.isFile) return PatchedType.NONE

        return runCatching {
            ZipFile(sourceFile).use { zip ->
                when {
                    zip.getEntry("assets/npatch/config.json") != null || zip.getEntry("assets/npatch/loader.bin") != null || zip.getEntry("assets/npatch/origin.apk") != null -> PatchedType.NPATCH
                    zip.getEntry("assets/lspatch/config.json") != null || zip.getEntry("assets/lspatch/loader.bin") != null || zip.getEntry("assets/lspatch/origin.apk") != null -> PatchedType.LSPATCH
                    zip.getEntry("fpa/config.json") != null || zip.getEntry("assets/fpa/config.json") != null || zip.getEntry("extra/core.dex") != null || zip.getEntry("fpa/source.apk") != null -> PatchedType.FPA
                    zip.getEntry("assets/origin.apk") != null -> PatchedType.EMBEDDED
                    zip.entries().asSequence().any { !it.isDirectory && it.name.startsWith("assets/") && it.name.endsWith(".apk") } -> PatchedType.EMBEDDED
                    else -> PatchedType.NONE
                }
            }
        }.getOrDefault(PatchedType.NONE)
    }

    suspend fun extractOriginalApk(patchedApp: AppInfo): ExtractResult {
        return withContext(Dispatchers.IO) {
            val sourceDir = patchedApp.app.sourceDir
            if (sourceDir.isNullOrEmpty()) {
                return@withContext ExtractResult.Error("Source path is empty", null)
            }
            val sourceFile = File(sourceDir)
            if (!sourceFile.isFile) {
                return@withContext ExtractResult.Error("Source APK file not found: $sourceDir", null)
            }

            val targetType = detectPatchedTypeDeep(patchedApp)
            val candidatePaths = when (targetType) {
                PatchedType.NPATCH -> listOf(
                    "assets/npatch/origin.apk",
                    "assets/origin.apk",
                    "assets/lspatch/origin.apk",
                    "fpa/source.apk",
                    "fpa/o_app.apk"
                )
                PatchedType.LSPATCH -> listOf(
                    "assets/lspatch/origin.apk",
                    "assets/origin.apk",
                    "assets/npatch/origin.apk",
                    "fpa/source.apk",
                    "fpa/o_app.apk"
                )
                PatchedType.FPA -> listOf(
                    "fpa/source.apk",
                    "fpa/o_app.apk",
                    "assets/fpa/source.apk",
                    "assets/fpa/o_app.apk",
                    "assets/npatch/origin.apk",
                    "assets/lspatch/origin.apk"
                )
                PatchedType.EMBEDDED, PatchedType.NONE -> listOf(
                    "assets/origin.apk",
                    "assets/npatch/origin.apk",
                    "assets/lspatch/origin.apk",
                    "fpa/source.apk",
                    "fpa/o_app.apk",
                    "assets/fpa/source.apk"
                )
            }

            val extractedFile = uniqueTempFile(sanitizeVisibleFileName(patchedApp.label + "_original.apk"))
            var extractedEntryName: String? = null

            try {
                ZipFile(sourceFile).use { zip ->
                    for (path in candidatePaths) {
                        val entry = zip.getEntry(path)
                        if (entry != null) {
                            extractedEntryName = path
                            zip.getInputStream(entry).use { input ->
                                extractedFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            break
                        }
                    }

                    if (extractedEntryName == null) {
                        val anyEmbedded = zip.entries().asSequence().firstOrNull {
                            !it.isDirectory && it.name.startsWith("assets/") && it.name.endsWith(".apk")
                        }
                        if (anyEmbedded != null) {
                            extractedEntryName = anyEmbedded.name
                            zip.getInputStream(anyEmbedded).use { input ->
                                extractedFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            } catch (t: java.util.zip.ZipException) {
                extractedFile.delete()
                return@withContext ExtractResult.Corrupted("Corrupted APK archive", t)
            } catch (t: Throwable) {
                extractedFile.delete()
                return@withContext ExtractResult.Error("Extraction failed: ${t.message}", t)
            }

            if (extractedEntryName == null || !extractedFile.exists() || extractedFile.length() == 0L) {
                extractedFile.delete()
                return@withContext ExtractResult.NoOriginalApk
            }

            try {
                val pm = lspApp.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(
                    extractedFile.absolutePath,
                    PackageManager.GET_META_DATA or PackageManager.GET_SIGNING_CERTIFICATES
                ) ?: run {
                    extractedFile.delete()
                    return@withContext ExtractResult.Corrupted("Failed to parse extracted original APK", null)
                }

                val appInfo = pkgInfo.applicationInfo ?: run {
                    extractedFile.delete()
                    return@withContext ExtractResult.Corrupted("Extracted APK contains no application info", null)
                }

                appInfo.sourceDir = extractedFile.absolutePath
                appInfo.publicSourceDir = extractedFile.absolutePath
                appInfo.splitSourceDirs = null

                val innerLabel = runCatching { pm.getApplicationLabel(appInfo).toString() }
                    .getOrDefault(pkgInfo.packageName)
                val originalAppInfo = AppInfo(
                    app = appInfo,
                    label = innerLabel,
                    versionName = pkgInfo.versionName ?: "",
                    versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo),
                    moduleMetadata = ModuleMetadataReader.read(pkgInfo, pm)
                )

                val outerPkg = patchedApp.app.packageName
                val innerPkg = pkgInfo.packageName
                if (outerPkg != innerPkg) {
                    Log.w(TAG, "Extracted APK package mismatch: outer=$outerPkg, inner=$innerPkg")
                    return@withContext ExtractResult.PackageMismatch(outerPkg, innerPkg, originalAppInfo)
                }

                return@withContext ExtractResult.Success(originalAppInfo)
            } catch (t: Throwable) {
                extractedFile.delete()
                return@withContext ExtractResult.Error("Failed to validate extracted APK: ${t.message}", t)
            }
        }
    }
}
