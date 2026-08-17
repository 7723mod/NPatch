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

            // Step 1: Parse AndroidManifest.xml of all APKs via fast lightweight AXML parser.
            val manifestInfos = canonicalFiles.map { file ->
                val pair = readManifestInfo(file)
                if (pair.packageName.isNullOrBlank()) {
                    throw IOException("APK manifest has no package name: ${file.name}")
                }
                ManifestApk(file = file, packageName = pair.packageName, splitName = pair.splitName)
            }

            // Step 2: Find the unique base APK (splitName is null or empty).
            val baseCandidates = manifestInfos.filter { it.splitName.isNullOrBlank() }
            if (baseCandidates.isEmpty()) {
                throw IOException("APK install set has no base APK")
            }
            if (baseCandidates.size > 1) {
                throw IOException("APK install set contains multiple base APKs")
            }
            val baseInfo = baseCandidates.single()

            // Step 3: Parse base APK with system PackageManager to get versionCode + signature.
            val basePackageInfo = packageManager.getPackageArchiveInfo(
                baseInfo.file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ) ?: throw IOException("Unable to parse base APK: ${baseInfo.file.name}")

            val baseVersionCode = PackageInfoCompat.getLongVersionCode(basePackageInfo)
            val baseSignerDigest = (
                basePackageInfo.signingInfo?.apkContentsSigners
                    ?: @Suppress("DEPRECATION") basePackageInfo.signatures
            )?.map { signature -> sha256(signature.toByteArray()) }
                ?.sorted()
                ?.joinToString(":")
                ?.takeIf { it.isNotEmpty() }
                ?: readSignerDigest(baseInfo.file)

            // Step 4: Validate split APKs using ManifestParser results + ApkSignatureHelper.
            manifestInfos.filterNot { it === baseInfo }.forEach { split ->
                if (split.packageName != baseInfo.packageName) {
                    throw IOException(
                        "Mixed packages in APK set: ${baseInfo.packageName} and ${split.packageName}",
                    )
                }
                val splitSignerDigest = readSignerDigest(split.file)
                if (splitSignerDigest != baseSignerDigest) {
                    throw IOException("APK signatures do not match: ${split.file.name}")
                }
            }

            val duplicateSplit = manifestInfos
                .mapNotNull { it.splitName?.takeIf(String::isNotBlank) }
                .groupingBy(String::lowercase)
                .eachCount()
                .entries
                .firstOrNull { it.value > 1 }
            if (duplicateSplit != null) {
                throw IOException("Duplicate APK split: ${duplicateSplit.key}")
            }

            val ordered = listOf(baseInfo) + manifestInfos
                .filterNot { it === baseInfo }
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
            return ApkInstallSet(baseInfo.packageName, baseVersionCode, entries)
        }

        private fun readManifestInfo(file: File): ManifestParser.Pair =
            ZipFile(file).use { zip ->
                val manifest = zip.getEntry("AndroidManifest.xml")
                    ?: throw IOException("APK has no AndroidManifest.xml: ${file.name}")
                zip.getInputStream(manifest).use { input ->
                    ManifestParser.parseManifestFile(input)
                        ?: throw IOException("Unable to parse AndroidManifest.xml: ${file.name}")
                }
            }

        /**
         * Reads the signer digest of an APK file using [top.nkbe.npatch.patch.util.ApkSignatureHelper], which parses the
         * APK signing block directly without relying on Android's PackageParser. This allows it
         * to handle split config APKs that the system PackageManager cannot parse standalone.
         */
        private fun readSignerDigest(file: File): String {
            val signatures = top.nkbe.npatch.patch.util.ApkSignatureHelper.getApkSignatures(file.absolutePath)
            if (signatures.isNullOrEmpty()) {
                throw IOException("Unable to read APK signature: ${file.name}")
            }
            return signatures
                .map { certBytes -> sha256(certBytes) }
                .sorted()
                .joinToString(":")
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

        private data class ManifestApk(
            val file: File,
            val packageName: String,
            val splitName: String?,
        )
    }
}
