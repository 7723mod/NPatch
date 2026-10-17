package top.nkbe.npatch.manager

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Base64
import org.json.JSONObject
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nkbe.util.ShizukuApi
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticLogExporter {

    private const val MAX_FILES_PER_SOURCE = 12
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024
    private const val MAX_TOTAL_LOG_BYTES = 32L * 1024 * 1024
    private val safeSegmentPattern = Regex("[^A-Za-z0-9._-]")

    data class Result(
        val file: File,
        val collectedFiles: Int,
        val skippedFiles: Int,
    )

    /** Exports diagnostics belonging to one patched application, never manager-wide logs. */
    suspend fun export(context: Context, targetPackageName: String): Result = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val outputDirectory = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
        outputDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("npatch-diagnostics-") }
            ?.forEach(File::delete)

        val timestamp = utcFormat("yyyyMMdd-HHmmss").format(Date())
        val outputFile = File(outputDirectory, "npatch-diagnostics-${safeSegment(targetPackageName)}-$timestamp.zip")
        val errors = mutableListOf<String>()
        var collectedFiles = 0
        var skippedFiles = 0
        var totalLogBytes = 0L

        try {
            ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
                zip.putText("diagnostics.txt", buildMetadata(appContext, targetPackageName))

                val mediaRoot = File(Environment.getExternalStorageDirectory(), "Android/media")
                val result = zip.addLogDirectory(
                    directory = File(mediaRoot, "$targetPackageName/npatch/log"),
                    zipPrefix = "app/${safeSegment(targetPackageName)}",
                    remainingBytes = MAX_TOTAL_LOG_BYTES,
                )
                collectedFiles += result.collected
                skippedFiles += result.skipped
                totalLogBytes += result.bytes
                errors += result.errors

                val logcatDump = captureLogcatViaShizuku(targetPackageName)
                if (logcatDump != null) {
                    zip.putText("app/${safeSegment(targetPackageName)}/logcat.log", logcatDump)
                    collectedFiles++
                    totalLogBytes += logcatDump.toByteArray(StandardCharsets.UTF_8).size
                }

                zip.putText(
                    "collection-report.txt",
                    buildString {
                        appendLine("Collected log files: $collectedFiles")
                        appendLine("Skipped log files: $skippedFiles")
                        appendLine("Collected log bytes: $totalLogBytes")
                        if (logcatDump != null) {
                            appendLine("Included system logcat via Shizuku: Yes")
                        } else {
                            appendLine("Included system logcat via Shizuku: No (Shizuku not ready or process capture skipped)")
                        }
                        if (collectedFiles == 0) {
                            appendLine()
                            appendLine("Notice:")
                            appendLine("No log files were found in /sdcard/Android/media/$targetPackageName/npatch/log/.")
                            appendLine("Possible reasons:")
                            appendLine("1. The app has not been launched yet.")
                            appendLine("2. The app crashed before NPatch loader initialized (e.g., manifest/signature parse error or native crash).")
                            appendLine("3. 'Output Log to Media Directory' was disabled during patching.")
                        }
                        if (errors.isNotEmpty()) {
                            appendLine()
                            appendLine("Collection warnings:")
                            errors.distinct().forEach { appendLine("- $it") }
                        }
                    },
                )
            }
            Result(outputFile, collectedFiles, skippedFiles)
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }

    private fun buildMetadata(context: Context, targetPackageName: String): String {
        val packageInfo = context.packageManager.getPackageInfo(targetPackageName, 0)
        return buildString {
            appendLine("NPatch patched-app diagnostic package")
            appendLine("Generated (UTC): ${utcFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date())}")
            appendLine("Patched application: $targetPackageName")
            appendLine("Application version: ${packageInfo.versionName} (${PackageInfoCompat.getLongVersionCode(packageInfo)})")
            appendLine("Signature bypass level: ${readSigBypassLevel(context, targetPackageName) ?: "unknown"}")
            appendLine()
            appendLine("Contents may include LoadedModule output, stack traces and app-provided log messages.")
            appendLine("Review the archive before sharing it publicly.")
        }
    }


    private fun readSigBypassLevel(context: Context, targetPackageName: String): Int? = runCatching {
        val encoded = context.packageManager
            .getApplicationInfo(targetPackageName, PackageManager.GET_META_DATA)
            .metaData?.getString("npatch") ?: return@runCatching null
        val json = String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8)
        JSONObject(json).optInt("sigBypassLevel", -1).takeIf { it >= 0 }
    }.getOrNull()

    private data class DirectoryResult(
        val collected: Int,
        val skipped: Int,
        val bytes: Long,
        val errors: List<String>,
    )

    private fun ZipOutputStream.addLogDirectory(
        directory: File,
        zipPrefix: String,
        remainingBytes: Long,
    ): DirectoryResult {
        if (remainingBytes <= 0L) {
            return DirectoryResult(0, 0, 0L, emptyList())
        }
        // Without all-files access another app's non-media files are invisible, so an empty result
        // must not be reported as "no logs".
        val accessWarnings = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
        ) {
            listOf("${directory.path}: all files access is not granted to NPatch; existing logs may be invisible")
        } else {
            emptyList()
        }
        if (!directory.isDirectory) {
            return DirectoryResult(0, 0, 0L, accessWarnings)
        }
        if (directory.list() == null) {
            return DirectoryResult(0, 0, 0L, accessWarnings + "${directory.path}: directory cannot be listed")
        }
        val canonicalRoot = runCatching { directory.canonicalFile }.getOrElse {
            return DirectoryResult(0, 0, 0L, listOf("${directory.path}: ${it.message}"))
        }
        val candidates = runCatching {
            canonicalRoot.walkTopDown()
                .filter { file ->
                    file.isFile && file.extension.lowercase(Locale.ROOT) in setOf("log", "txt")
                }
                .sortedByDescending(File::lastModified)
                .toList()
        }.getOrElse {
            return DirectoryResult(0, 0, 0L, listOf("${directory.path}: ${it.message}"))
        }
        if (candidates.isEmpty()) {
            return DirectoryResult(0, 0, 0L, accessWarnings)
        }

        var collected = 0
        var skipped = (candidates.size - MAX_FILES_PER_SOURCE).coerceAtLeast(0)
        var bytes = 0L
        val errors = mutableListOf<String>()
        candidates.take(MAX_FILES_PER_SOURCE).forEach { file ->
            if (bytes >= remainingBytes) {
                skipped++
                return@forEach
            }
            val canonicalFile = runCatching { file.canonicalFile }.getOrElse {
                errors += "${file.path}: ${it.message}"
                skipped++
                return@forEach
            }
            val relative = canonicalFile.relativeToOrNull(canonicalRoot)
            if (relative == null || relative.path.startsWith("..")) {
                skipped++
                return@forEach
            }
            val byteLimit = minOf(MAX_FILE_BYTES, remainingBytes - bytes)
            val content = runCatching { canonicalFile.readTail(byteLimit) }.getOrElse {
                errors += "${file.path}: ${it.message}"
                skipped++
                return@forEach
            }
            val entryName = "$zipPrefix/${safeSegment(relative.path.replace(File.separatorChar, '_'))}"
            runCatching {
                putBytes(entryName, content, canonicalFile.lastModified())
            }.onSuccess {
                collected++
                bytes += content.size
            }.onFailure {
                errors += "$entryName: ${it.message}"
                skipped++
            }
        }
        return DirectoryResult(collected, skipped, bytes, errors)
    }

    private fun File.readTail(limit: Long): ByteArray {
        RandomAccessFile(this, "r").use { input ->
            val length = input.length()
            val count = minOf(length, limit).toInt()
            input.seek(length - count)
            return ByteArray(count).also(input::readFully)
        }
    }


    private fun ZipOutputStream.putText(name: String, text: String) {
        putBytes(name, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray, timestamp: Long = 0L) {
        putNextEntry(ZipEntry(name).apply { time = timestamp })
        write(bytes)
        closeEntry()
    }

    private fun safeSegment(value: String): String = value
        .replace(safeSegmentPattern, "_")
        .trim('.')
        .ifEmpty { "unknown" }

    private suspend fun captureLogcatViaShizuku(targetPackageName: String): String? {
        return ShizukuApi.dumpLogcat(targetPackageName, 5000)
    }

    private fun utcFormat(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}
