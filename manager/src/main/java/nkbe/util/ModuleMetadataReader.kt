package nkbe.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.LinkedHashSet
import java.util.Properties
import java.util.zip.ZipFile

enum class ModulePipeline {
    LEGACY,
    MODERN,
    UNSUPPORTED,
}

@Parcelize
data class ModuleMetadataSnapshot(
    val packageName: String,
    val displayName: String,
    val description: String,
    val minApiVersion: Int,
    val targetApiVersion: Int,
    val staticScope: Boolean,
    val author: String,
    val version: String,
    val scopes: List<String>,
    val javaInitList: List<String>,
    val nativeInitList: List<String>,
    val pipeline: ModulePipeline,
) : Parcelable {
    val isModern: Boolean
        get() = pipeline == ModulePipeline.MODERN

    val isLegacy: Boolean
        get() = pipeline == ModulePipeline.LEGACY

    val isUnsupported: Boolean
        get() = pipeline == ModulePipeline.UNSUPPORTED
}

object ModuleMetadataReader {
    private const val MODERN_MODULE_PROP = "META-INF/xposed/module.prop"
    private const val MODERN_SCOPE_LIST = "META-INF/xposed/scope.list"
    private const val MODERN_JAVA_INIT_LIST = "META-INF/xposed/java_init.list"
    private const val MODERN_NATIVE_INIT_LIST = "META-INF/xposed/native_init.list"
    private const val LEGACY_JAVA_INIT_LIST = "assets/xposed_init"
    private const val LEGACY_NATIVE_INIT_LIST = "assets/native_init"

    private const val KEY_MIN_API_VERSION = "minApiVersion"
    private const val KEY_TARGET_API_VERSION = "targetApiVersion"
    private const val KEY_STATIC_SCOPE = "staticScope"
    private const val KEY_AUTHOR = "author"
    private const val KEY_VERSION = "version"

    private const val LEGACY_KEY_MIN_API_VERSION = "xposedminversion"
    private const val LEGACY_KEY_TARGET_API_VERSION = "xposedtargetversion"
    private const val LEGACY_KEY_STATIC_SCOPE = "xposedstaticscope"
    private const val LEGACY_KEY_AUTHOR = "xposedauthor"
    private const val LEGACY_KEY_VERSION = "xposedversion"
    private const val LEGACY_KEY_NAME = "xposedname"
    private const val LEGACY_KEY_DESCRIPTION = "xposeddescription"
    private const val LEGACY_KEY_SCOPES = "xposedscope"

    private const val FRAMEWORK_API_VERSION = 101
    private const val MODERN_TARGET_API_VERSION = 101
    private const val LEGACY_MAX_API_VERSION = 94

    fun read(appInfo: ApplicationInfo, packageManager: PackageManager): ModuleMetadataSnapshot? {
        val apkPath = appInfo.sourceDir ?: return null
        val apkFile = File(apkPath)
        if (!apkFile.exists()) return null

        val packageInfo = runCatching {
            packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_META_DATA)
        }.getOrNull()
        packageInfo?.applicationInfo?.apply {
            sourceDir = apkFile.absolutePath
            publicSourceDir = apkFile.absolutePath
        }

        // Patched apps can embed module assets for their own runtime, but they are not standalone
        // Xposed modules and should stay in app management instead of the module list.
        if (packageInfo?.applicationInfo?.metaData?.containsKey("npatch") == true) {
            return null
        }

        val legacyMeta = packageInfo?.applicationInfo?.metaData
        val modernProps = Properties()
        val modernJavaInitList = mutableListOf<String>()
        val modernNativeInitList = mutableListOf<String>()
        val legacyJavaInitList = mutableListOf<String>()
        val legacyNativeInitList = mutableListOf<String>()
        val scopes = mutableListOf<String>()

        runCatching {
            ZipFile(apkFile).use { zipFile ->
                loadProperties(zipFile, modernProps)
                readList(zipFile, MODERN_SCOPE_LIST, scopes)
                readList(zipFile, MODERN_JAVA_INIT_LIST, modernJavaInitList)
                readList(zipFile, MODERN_NATIVE_INIT_LIST, modernNativeInitList)
                readList(zipFile, LEGACY_JAVA_INIT_LIST, legacyJavaInitList)
                readList(zipFile, LEGACY_NATIVE_INIT_LIST, legacyNativeInitList)
            }
        }.getOrElse {
            return null
        }

        val minApiVersion = readInt(
            modernProps,
            KEY_MIN_API_VERSION,
            legacyMeta?.get(LEGACY_KEY_MIN_API_VERSION),
            0,
        )
        val targetApiVersion = readInt(
            modernProps,
            KEY_TARGET_API_VERSION,
            legacyMeta?.get(LEGACY_KEY_TARGET_API_VERSION),
            minApiVersion,
        )
        val staticScope = readBoolean(
            modernProps,
            KEY_STATIC_SCOPE,
            legacyMeta?.get(LEGACY_KEY_STATIC_SCOPE),
            false,
        )
        val author = firstNonEmpty(
            readString(modernProps, KEY_AUTHOR),
            legacyMeta?.get(LEGACY_KEY_AUTHOR)?.toString(),
        )
        val version = firstNonEmpty(
            readString(modernProps, KEY_VERSION),
            legacyMeta?.get(LEGACY_KEY_VERSION)?.toString(),
        )

        val hasModernEntrypoint = modernJavaInitList.isNotEmpty() || modernNativeInitList.isNotEmpty()
        val hasLegacyEntrypoint = legacyJavaInitList.isNotEmpty() || legacyNativeInitList.isNotEmpty()
        val hasModernMetadata = modernProps.isNotEmpty() || hasModernEntrypoint || scopes.isNotEmpty()
        val hasLegacyMetadata = legacyMeta?.containsKey(LEGACY_KEY_MIN_API_VERSION) == true || legacyMeta?.containsKey(LEGACY_KEY_DESCRIPTION) == true

        val pipeline = when {
            hasModernEntrypoint &&
                minApiVersion <= FRAMEWORK_API_VERSION &&
                targetApiVersion >= MODERN_TARGET_API_VERSION -> ModulePipeline.MODERN
            hasLegacyEntrypoint && minApiVersion <= LEGACY_MAX_API_VERSION -> ModulePipeline.LEGACY
            else -> ModulePipeline.UNSUPPORTED
        }

        val javaInitList = when (pipeline) {
            ModulePipeline.MODERN -> modernJavaInitList
            ModulePipeline.LEGACY -> legacyJavaInitList
            ModulePipeline.UNSUPPORTED -> emptyList()
        }
        val nativeInitList = when (pipeline) {
            ModulePipeline.MODERN -> modernNativeInitList
            ModulePipeline.LEGACY -> legacyNativeInitList
            ModulePipeline.UNSUPPORTED -> emptyList()
        }

        if (pipeline != ModulePipeline.MODERN && scopes.isEmpty()) {
            readLegacyScopeList(legacyMeta?.get(LEGACY_KEY_SCOPES), scopes)
        }

        if (!hasModernMetadata && !hasLegacyEntrypoint && !hasLegacyMetadata) return null

        val displayName = firstNonEmpty(
            loadLabel(packageInfo?.applicationInfo, packageManager),
            legacyMeta?.get(LEGACY_KEY_NAME)?.toString(),
            appInfo.packageName,
        )
        val description = firstNonEmpty(
            loadDescription(packageInfo?.applicationInfo, packageManager),
            legacyMeta?.get(LEGACY_KEY_DESCRIPTION)?.toString(),
        )

        return ModuleMetadataSnapshot(
            packageName = packageInfo?.packageName ?: appInfo.packageName,
            displayName = displayName,
            description = description,
            minApiVersion = minApiVersion,
            targetApiVersion = targetApiVersion,
            staticScope = staticScope,
            author = author,
            version = version,
            scopes = scopes.toList(),
            javaInitList = javaInitList.toList(),
            nativeInitList = nativeInitList.toList(),
            pipeline = pipeline,
        )
    }

    private fun loadProperties(zipFile: ZipFile, properties: Properties) {
        val entry = zipFile.getEntry(MODERN_MODULE_PROP) ?: return
        zipFile.getInputStream(entry).use { input ->
            properties.load(InputStreamReader(input, StandardCharsets.UTF_8))
        }
    }

    private fun readList(zipFile: ZipFile, entryName: String, out: MutableList<String>) {
        val entry = zipFile.getEntry(entryName) ?: return
        zipFile.getInputStream(entry).use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val value = line.trim()
                    if (value.isEmpty() || value.startsWith("#")) continue
                    out.add(value)
                }
            }
        }
    }

    private fun readLegacyScopeList(rawValue: Any?, out: MutableList<String>) {
        val value = rawValue?.toString()?.trim().orEmpty()
        if (value.isEmpty()) return
        val deduplicated = LinkedHashSet<String>()
        value.split(Regex("[\\s,;]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { deduplicated.add(it) }
        out.addAll(deduplicated)
    }

    private fun loadLabel(applicationInfo: ApplicationInfo?, packageManager: PackageManager): String? {
        if (applicationInfo == null) return null
        return runCatching { applicationInfo.loadLabel(packageManager)?.toString()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun loadDescription(applicationInfo: ApplicationInfo?, packageManager: PackageManager): String? {
        if (applicationInfo == null) return null
        return runCatching { applicationInfo.loadDescription(packageManager)?.toString()?.trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun readString(properties: Properties, key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

    private fun readInt(properties: Properties, modernKey: String, legacyValue: Any?, defaultValue: Int): Int {
        readString(properties, modernKey)?.toIntOrNull()?.let { return it }
        legacyValue?.toString()?.trim()?.toIntOrNull()?.let { return it }
        return defaultValue
    }

    private fun readBoolean(properties: Properties, modernKey: String, legacyValue: Any?, defaultValue: Boolean): Boolean {
        readString(properties, modernKey)?.let { return it.toBoolean() }
        legacyValue?.toString()?.trim()?.let { return it.toBoolean() }
        return defaultValue
    }

    private fun firstNonEmpty(vararg values: String?): String {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }
}
