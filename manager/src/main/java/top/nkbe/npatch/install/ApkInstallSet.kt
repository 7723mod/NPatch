package top.nkbe.npatch.install

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import top.nkbe.npatch.patch.util.ManifestParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

data class ApkInstallSet(
    val packageName: String,
    val versionCode: Long,
    val entries: List<Entry>,
) {
    data class Entry(
        val file: File,
        val sessionName: String,
    )

    val totalSize: Long
        get() = entries.sumOf { it.file.length() }

    companion object {
        fun fromFiles(context: Context, apkFiles: List<File>): ApkInstallSet {
            if (apkFiles.isEmpty()) throw IOException("APK install set is empty")
            val canonicalFiles = apkFiles.map { file ->
                file.canonicalFile.also {
                    if (!it.isFile || !it.name.endsWith(".apk", ignoreCase = true)) {
                        throw IOException("Invalid APK file: $it")
                    }
                    if (it.length() <= 0L) throw IOException("Empty APK file: $it")
                }
            }
            if (canonicalFiles.distinct().size != canonicalFiles.size) {
                throw IOException("APK install set contains duplicate files")
            }

            val packageManager = context.packageManager
            val parsed = canonicalFiles.map { file ->
                val packageInfo = packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ) ?: throw IOException("Unable to parse APK: ${file.name}")
                ParsedApk(
                    file = file,
                    packageName = packageInfo.packageName,
                    versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
                    splitName = readSplitName(file),
                    signerDigest = packageInfo.signingInfo
                        ?.apkContentsSigners
                        ?.map { signature -> sha256(signature.toByteArray()) }
                        ?.sorted()
                        ?.joinToString(":")
                        .orEmpty(),
                )
            }

            val baseCandidates = parsed.filter { it.splitName.isNullOrBlank() }
            if (baseCandidates.size != 1) {
                throw IOException("APK install set must contain exactly one base APK")
            }
            val base = baseCandidates.single()
            parsed.filterNot { it === base }.forEach { split ->
                if (split.packageName != base.packageName) {
                    throw IOException(
                        "Mixed packages in APK set: ${base.packageName} and ${split.packageName}",
                    )
                }
                if (split.versionCode != base.versionCode) {
                    throw IOException(
                        "Mixed version codes in APK set: ${base.versionCode} and ${split.versionCode}",
                    )
                }
                if (split.signerDigest != base.signerDigest) {
                    throw IOException("APK signatures do not match: ${split.file.name}")
                }
            }
            val duplicateSplit = parsed
                .mapNotNull { it.splitName?.takeIf(String::isNotBlank) }
                .groupingBy(String::lowercase)
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
            if (duplicateSplit != null) {
                throw IOException("Duplicate APK split: ${duplicateSplit.key}")
            }

            val ordered = listOf(base) + parsed
                .filterNot { it === base }
                .sortedBy { it.splitName?.lowercase() }
            val entries = ordered.map { apk ->
                Entry(
                    file = apk.file,
                    sessionName = apk.splitName
                        ?.takeIf(String::isNotBlank)
                        ?.let(::splitSessionName)
                        ?: "base.apk",
                )
            }
            return ApkInstallSet(base.packageName, base.versionCode, entries)
        }

        private fun readSplitName(file: File): String? = ZipFile(file).use { zip ->
            val manifest = zip.getEntry("AndroidManifest.xml")
                ?: throw IOException("APK has no AndroidManifest.xml: ${file.name}")
            zip.getInputStream(manifest).use { input ->
                val parsed = ManifestParser.parseManifestFile(input)
                    ?: throw IOException("Unable to parse AndroidManifest.xml: ${file.name}")
                if (parsed.packageName.isNullOrBlank()) {
                    throw IOException("APK manifest has no package name: ${file.name}")
                }
                parsed.splitName
            }
        }

        private fun splitSessionName(splitName: String): String {
            val safeName = splitName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (safeName.isBlank()) throw IOException("Invalid APK split name")
            return "split_$safeName.apk"
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private data class ParsedApk(
            val file: File,
            val packageName: String,
            val versionCode: Long,
            val splitName: String?,
            val signerDigest: String,
        )
    }
}
