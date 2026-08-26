package top.nkbe.npatch

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.KeystorePreset
import top.nkbe.npatch.config.MyKeyStore
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.patch.NPatch
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.patch.util.ManifestParser
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Patcher {

    class Options(
        val newPackageName: String,
        private val config: PatchConfig,
        private val apkPaths: List<String>,
        private val embeddedModules: List<String>?,
        val targetPackageName: String? = null,
        val embeddedModulePackages: List<String>? = null,
        private val injectDex: Boolean = false
    ) {
        internal val inputApks: List<File>
            get() = resolveActualApkPaths().map { File(it).absoluteFile }

        fun resolveActualApkPaths(): List<String> {
            // Re-resolve only apks the system keeps and can move on an update. A recorded path under this
            // app's own storage is a source copy it owns -- an apk picked from storage, an extracted
            // original apk or a custom local archive -- and reading the installed apk in its place would
            // silently patch a different build than the one chosen by the user.
            if (apkPaths.any { isAppOwnedPath(it) }) {
                return apkPaths
            }
            val pkg = targetPackageName
            if (pkg != null) {
                val resolved = resolvePackageApks(pkg)
                if (resolved.isNotEmpty()) {
                    return resolved
                }
            }
            return apkPaths
        }

        fun resolveActualEmbeddedModules(): List<String>? {
            val modulePkgs = embeddedModulePackages
            if (!modulePkgs.isNullOrEmpty()) {
                val resolvedList = modulePkgs.flatMap { pkg ->
                    val owned = embeddedModules?.filter { isAppOwnedPath(it) && it.contains(pkg) } ?: emptyList()
                    if (owned.isNotEmpty()) {
                        owned
                    } else {
                        val resolved = resolvePackageApks(pkg)
                        if (resolved.isNotEmpty()) resolved
                        else embeddedModules?.filter { it.contains(pkg) } ?: emptyList()
                    }
                }
                if (resolvedList.isNotEmpty()) return resolvedList
            }
            return embeddedModules
        }

        private fun isAppOwnedPath(path: String): Boolean {
            val dataPath = lspApp.applicationInfo.dataDir
            val noBackupPath = lspApp.noBackupFilesDir.path
            val cachePath = lspApp.cacheDir.path
            val filesPath = lspApp.filesDir.path
            val extCachePath = lspApp.externalCacheDir?.path
            return (dataPath != null && path.startsWith(dataPath)) ||
                    path.startsWith(noBackupPath) ||
                    path.startsWith(cachePath) ||
                    path.startsWith(filesPath) ||
                    (extCachePath != null && path.startsWith(extCachePath))
        }

        private fun resolvePackageApks(packageName: String): List<String> {
            return runCatching {
                val appInfo = lspApp.packageManager.getApplicationInfo(packageName, 0)
                val base = appInfo.sourceDir
                if (base != null && File(base).exists()) {
                    val splits = appInfo.splitSourceDirs?.filter { File(it).exists() } ?: emptyList()
                    listOf(base) + splits
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
        }

        fun toStringArray(): Array<String> {
            val actualApks = resolveActualApkPaths()
            val actualModules = resolveActualEmbeddedModules()
            return buildList {
                add("-o"); add(lspApp.tmpApkDir.absolutePath)
                add("-p"); add(config.newPackage)
                if (config.debuggable) add("-d")
                add("-l"); add(config.sigBypassLevel.toString())
                if (config.useManager) add("--manager")
                if (injectDex) add("--injectdex")
                if (config.overrideVersionCode) {
                    add("--allowdown")
                    add("--versioncode")
                    add(config.overrideVersionCodeValue.toString())
                }
                if (config.overrideTargetSdk) {
                    add("--override-target-sdk")
                    add("--target-sdk")
                    add(config.overrideTargetSdkValue.toString())
                }
                if (Configs.detailPatchLogs) add("-v")
                add("--force")
                actualModules?.forEach {
                    add("-m"); add(it)
                }
                if (config.injectProvider) add("--provider")
                if (config.useMicroG) add("--useMicroG")
                if (config.hideLibs) add("--hidelibs")
                if (config.usesCleartextTraffic) add("--cleartext")
                when (Configs.keyStorePreset) {
                    KeystorePreset.NPATCH -> add("-npa")
                    KeystorePreset.FPA -> add("-fpa")
                    KeystorePreset.CUSTOM -> addAll(arrayOf("-k", MyKeyStore.file.path, Configs.keyStorePassword, Configs.keyStoreAlias, Configs.keyStoreAliasPassword))
                }
                addAll(actualApks)
            }.toTypedArray()
        }
    }

    suspend fun patch(logger: Logger, options: Options) {
        withContext(Dispatchers.IO) {
            val inputApks = options.inputApks
            validateInputSet(inputApks)
            val outputsBeforePatch = currentPatchOutputs()
            NPatch(logger, *options.toStringArray()).doCommandLine()

            val uri = Configs.storageDirectory?.toUri()
                ?: throw IOException("Uri is null")
            val root = DocumentFile.fromTreeUri(lspApp, uri)
                ?: throw IOException("DocumentFile is null")
            val producedOutputs = currentPatchOutputs() - outputsBeforePatch
            val orderedOutputs = matchOutputsToInputs(inputApks, producedOutputs)
            val installDir = createInstallSetDirectory()
            val apkFileList = orderedOutputs.map { tempApkFile ->
                moveToInstallSet(tempApkFile, installDir.resolve(tempApkFile.name))
            }

            try {
                if (apkFileList.size == 1) {
                    val patchedApkFile = apkFileList.first()
                    exportFile(
                        root = root,
                        source = patchedApkFile,
                        mimeType = "application/vnd.android.package-archive",
                        outputName = patchedApkFile.name,
                    )
                    logger.i("Patched apk is saved to ${root.uri.lastPathSegment}/${patchedApkFile.name}")
                } else {
                    val archiveName = buildArchiveName(options.newPackageName)
                    val localArchive = installDir.resolve(archiveName + ".tmp")
                    localArchive.outputStream().use { output ->
                        createApksArchive(output, apkFileList)
                    }
                    exportFile(
                        root = root,
                        source = localArchive,
                        mimeType = "application/octet-stream",
                        outputName = archiveName,
                    )
                    localArchive.delete()
                    logger.i("Patched archive is saved to ${root.uri.lastPathSegment}/$archiveName")
                }
                replaceInstallSet(apkFileList)
            } catch (error: Throwable) {
                installDir.deleteRecursively()
                throw error
            }
        }
    }

    private fun validateInputSet(inputApks: List<File>) {
        if (inputApks.isEmpty()) throw IOException("No input APK files provided")
        val missing = inputApks.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw IOException("Input APK does not exist: ${missing.first().path}" + if (missing.size > 1) " (and ${missing.size - 1} more)" else "")
        }
        val duplicateNames = inputApks
            .groupBy { it.nameWithoutExtension.lowercase() }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateNames.isNotEmpty()) {
            throw IOException("Input APK names are ambiguous: ${duplicateNames.joinToString()}")
        }
    }

    private fun currentPatchOutputs(): Set<File> = lspApp.tmpApkDir
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(Constants.PATCH_FILE_SUFFIX) }
        .map { it.absoluteFile }
        .toSet()

    private fun matchOutputsToInputs(inputApks: List<File>, producedOutputs: Set<File>): List<File> {
        if (producedOutputs.size != inputApks.size) {
            throw IOException(
                "Patched APK count mismatch: expected ${inputApks.size}, got ${producedOutputs.size}",
            )
        }
        val unmatched = producedOutputs.toMutableSet()
        return inputApks.map { input ->
            val outputPattern = Regex(
                "^${Regex.escape(input.nameWithoutExtension)}-[0-9\\p{Nd}]+" +
                    "${Regex.escape(Constants.PATCH_FILE_SUFFIX)}$",
                RegexOption.IGNORE_CASE,
            )
            val matches = unmatched.filter { outputPattern.matches(it.name) }
            if (matches.size != 1) {
                throw IOException("Cannot match patched output for ${input.name}")
            }
            matches.single().also(unmatched::remove)
        }.also {
            if (unmatched.isNotEmpty()) {
                throw IOException("Unexpected patched outputs: ${unmatched.joinToString { file -> file.name }}")
            }
        }
    }

    private fun createInstallSetDirectory(): File {
        val cacheRoot = lspApp.externalCacheDir ?: lspApp.cacheDir
        val installRoot = cacheRoot.resolve("npatch-install")
        if (!installRoot.exists() && !installRoot.mkdirs()) {
            throw IOException("Unable to create install cache: $installRoot")
        }
        return installRoot.resolve(UUID.randomUUID().toString()).also {
            if (!it.mkdirs()) throw IOException("Unable to create install set: $it")
        }
    }

    private fun moveToInstallSet(source: File, destination: File): File {
        if (source.renameTo(destination)) return destination
        source.copyTo(destination, overwrite = false)
        if (!source.delete()) {
            destination.delete()
            throw IOException("Unable to remove temporary patched APK: $source")
        }
        return destination
    }

    private fun replaceInstallSet(apkFiles: List<File>) {
        val previous = lspApp.targetApkFiles.orEmpty().toList()
        val currentDirectory = apkFiles.first().parentFile?.absoluteFile
        lspApp.targetApkFiles = ArrayList(apkFiles)
        previous
            .mapNotNull(File::getParentFile)
            .map(File::getAbsoluteFile)
            .filter { it != currentDirectory }
            .distinct()
            .forEach { directory ->
                if (directory.parentFile?.name == "npatch-install") {
                    directory.deleteRecursively()
                }
            }
    }

    private const val STREAM_BUFFER_SIZE = 64 * 1024

    private fun exportFile(
        root: DocumentFile,
        source: File,
        mimeType: String,
        outputName: String,
    ) {
        root.findFile(outputName)?.delete()
        val destination = root.createFile(mimeType, outputName)
            ?: throw IOException("Unable to create output file: $outputName")
        try {
            lspApp.contentResolver.openOutputStream(destination.uri, "w")?.buffered(STREAM_BUFFER_SIZE)?.use { output ->
                source.inputStream().buffered(STREAM_BUFFER_SIZE).use { input -> input.copyTo(output, STREAM_BUFFER_SIZE) }
            } ?: throw IOException("Unable to open an output stream: ${destination.uri}")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun buildArchiveName(packageName: String): String {
        return packageName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + Constants.PATCH_ARCHIVE_SUFFIX
    }

    private fun createApksArchive(output: OutputStream, apkFiles: List<File>) {
        require(apkFiles.isNotEmpty()) { "APK set is empty" }
        val entryNames = mutableSetOf<String>()
        val mappedEntries = apkFiles.map { apkFile ->
            val pair = runCatching {
                java.util.zip.ZipFile(apkFile).use { zip ->
                    val manifest = zip.getEntry("AndroidManifest.xml") ?: return@use null
                    zip.getInputStream(manifest).use { input ->
                        ManifestParser.parseManifestFile(input)
                    }
                }
            }.getOrNull()

            val desiredName = when {
                pair == null -> apkFile.name
                pair.splitName.isNullOrEmpty() -> "base.apk"
                else -> "split_${pair.splitName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"
            }

            var finalName = desiredName
            var index = 1
            while (entryNames.contains(finalName.lowercase(java.util.Locale.ROOT))) {
                val base = desiredName.substringBeforeLast('.', desiredName)
                val ext = desiredName.substringAfterLast('.', "")
                finalName = if (ext.isEmpty()) "${base}_$index" else "${base}_$index.$ext"
                index++
            }
            entryNames.add(finalName.lowercase(java.util.Locale.ROOT))
            Pair(apkFile, finalName)
        }

        java.io.BufferedOutputStream(output, STREAM_BUFFER_SIZE).use { bufferedOut ->
            ZipOutputStream(bufferedOut).use { zip ->
                zip.setLevel(Deflater.NO_COMPRESSION)
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                mappedEntries.forEach { (apkFile, entryName) ->
                    val entry = ZipEntry(entryName).apply {
                        time = 0L
                        size = apkFile.length()
                    }
                    zip.putNextEntry(entry)
                    apkFile.inputStream().buffered(STREAM_BUFFER_SIZE).use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } >= 0) {
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
}
