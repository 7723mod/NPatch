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
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Patcher {

    class Options(
        val newPackageName: String,
        private val config: PatchConfig,
        private val apkPaths: List<String>,
        private val embeddedModules: List<String>?
    ) {
        fun toStringArray(): Array<String> {
            return buildList {
                add("-o"); add(lspApp.tmpApkDir.absolutePath)
                add("-p"); add(config.newPackage)
                if (config.debuggable) add("-d")
                add("-l"); add(config.sigBypassLevel.toString())
                if (config.useManager) add("--manager")
                if (config.overrideVersionCode) {
                    add("-r")
                    add("--versioncode"); add(config.overrideVersionCodeValue.toString())
                }
                if (Configs.detailPatchLogs) add("-v")
                embeddedModules?.forEach {
                    add("-m"); add(it)
                }
                if (config.injectProvider) add("--provider")
                if (config.useMicroG) add("--useMicroG")
                if (config.hideLibs) add("--hidelibs")
                when (Configs.keyStorePreset) {
                    KeystorePreset.NPATCH -> add("-npa")
                    KeystorePreset.FPA -> add("-fpa")
                    KeystorePreset.CUSTOM -> addAll(arrayOf("-k", MyKeyStore.file.path, Configs.keyStorePassword, Configs.keyStoreAlias, Configs.keyStoreAliasPassword))
                }
                addAll(apkPaths)
            }.toTypedArray()
        }
    }

    suspend fun patch(logger: Logger, options: Options) {
        withContext(Dispatchers.IO) {
            NPatch(logger, *options.toStringArray()).doCommandLine()

            val uri = Configs.storageDirectory?.toUri()
                ?: throw IOException("Uri is null")
            val root = DocumentFile.fromTreeUri(lspApp, uri)
                ?: throw IOException("DocumentFile is null")
            lspApp.targetApkFiles?.clear()
            val apkFileList = arrayListOf<File>()
            lspApp.tmpApkDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(Constants.PATCH_FILE_SUFFIX) }
                .forEach { tempApkFile ->
                    val cachedApkFile = File(lspApp.externalCacheDir, tempApkFile.name)
                    if (cachedApkFile.exists()) cachedApkFile.delete()
                    if (tempApkFile.renameTo(cachedApkFile).not()) {
                        tempApkFile.copyTo(cachedApkFile, overwrite = true)
                        tempApkFile.delete()
                    }
                    apkFileList.add(cachedApkFile)
                }
            if (apkFileList.isEmpty()) {
                throw IOException("No patched APK files found")
            }

            lspApp.targetApkFiles = apkFileList
            if (apkFileList.size == 1) {
                val patchedApkFile = apkFileList.first()
                root.findFile(patchedApkFile.name)?.delete()
                val finalApk = root.createFile("application/vnd.android.package-archive", patchedApkFile.name)
                    ?: throw IOException("Unable to create output file: ${patchedApkFile.name}")
                lspApp.contentResolver.openOutputStream(finalApk.uri)?.use { output ->
                    patchedApkFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Unable to open an output stream: ${finalApk.uri}")
                logger.i("Patched apk is saved to ${root.uri.lastPathSegment}/${patchedApkFile.name}")
            } else {
                val archiveName = buildArchiveName(options.newPackageName)
                root.findFile(archiveName)?.delete()
                val finalArchive = root.createFile("application/octet-stream", archiveName)
                    ?: throw IOException("Unable to create output file: $archiveName")
                lspApp.contentResolver.openOutputStream(finalArchive.uri)?.use { output ->
                    createApksArchive(output, apkFileList)
                } ?: throw IOException("Unable to open an output stream: ${finalArchive.uri}")
                logger.i("Patched archive is saved to ${root.uri.lastPathSegment}/$archiveName")
            }
        }
    }

    private fun buildArchiveName(packageName: String): String {
        return packageName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + Constants.PATCH_ARCHIVE_SUFFIX
    }

    private fun createApksArchive(output: OutputStream, apkFiles: List<File>) {
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            apkFiles.sortedBy { it.name }.forEach { apkFile ->
                zip.putNextEntry(ZipEntry(apkFile.name))
                apkFile.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }
}
