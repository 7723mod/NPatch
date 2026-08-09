package top.nkbe.npatch.loader.modern;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.matrix.vector.impl.utils.VectorMetaDataReader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ModuleMetadataParser {
    private static final String MODERN_MODULE_PROP = "META-INF/xposed/module.prop";
    private static final String MODERN_SCOPE_LIST = "META-INF/xposed/scope.list";
    private static final String MODERN_JAVA_INIT_LIST = "META-INF/xposed/java_init.list";
    private static final String MODERN_NATIVE_INIT_LIST = "META-INF/xposed/native_init.list";
    private static final String LEGACY_JAVA_INIT_LIST = "assets/xposed_init";
    private static final String LEGACY_NATIVE_INIT_LIST = "assets/native_init";

    private static final String KEY_MIN_API_VERSION = "minApiVersion";
    private static final String KEY_TARGET_API_VERSION = "targetApiVersion";
    private static final String KEY_STATIC_SCOPE = "staticScope";
    private static final String KEY_AUTHOR = "author";
    private static final String KEY_VERSION = "version";

    private static final String LEGACY_KEY_MIN_API_VERSION = "xposedminversion";
    private static final String LEGACY_KEY_TARGET_API_VERSION = "xposedtargetversion";
    private static final String LEGACY_KEY_STATIC_SCOPE = "xposedstaticscope";
    private static final String LEGACY_KEY_AUTHOR = "xposedauthor";
    private static final String LEGACY_KEY_VERSION = "xposedversion";
    private static final String LEGACY_KEY_NAME = "xposedname";
    private static final String LEGACY_KEY_DESCRIPTION = "xposeddescription";
    private static final String LEGACY_KEY_SCOPES = "xposedscope";
    private static final String LEGACY_KEY_PACKAGE_NAME = "xposedpackage";

    private final PackageManager packageManager;
    private final VersionRouter versionRouter = new VersionRouter();

    public ModuleMetadataParser(Context context) {
        this(context == null ? null : context.getPackageManager());
    }

    public ModuleMetadataParser(PackageManager packageManager) {
        this.packageManager = packageManager;
    }

    public ModuleMetadata parse(String apkPath) throws IOException {
        return parse(new File(apkPath));
    }

    public ModuleMetadata parse(File apkFile) throws IOException {
        Objects.requireNonNull(apkFile, "apkFile");
        if (!apkFile.exists()) {
            throw new IOException("APK not found: " + apkFile.getAbsolutePath());
        }

        Properties moduleProperties = new Properties();
        List<String> modernJavaInitList = new ArrayList<>();
        List<String> modernNativeInitList = new ArrayList<>();
        List<String> legacyJavaInitList = new ArrayList<>();
        List<String> legacyNativeInitList = new ArrayList<>();
        List<String> scopes = new ArrayList<>();

        try (ZipFile zipFile = new ZipFile(apkFile)) {
            loadProperties(zipFile, moduleProperties);
            modernJavaInitList.addAll(readList(zipFile, MODERN_JAVA_INIT_LIST));
            modernNativeInitList.addAll(readList(zipFile, MODERN_NATIVE_INIT_LIST));
            legacyJavaInitList.addAll(readList(zipFile, LEGACY_JAVA_INIT_LIST));
            legacyNativeInitList.addAll(readList(zipFile, LEGACY_NATIVE_INIT_LIST));
            scopes.addAll(readList(zipFile, MODERN_SCOPE_LIST));
        }

        Map<String, Object> legacyMetadata = readLegacyMetadata(apkFile);
        String packageName = readPackageName(apkFile, legacyMetadata);
        String name = firstNonEmpty(
                readLabelFromArchive(apkFile),
                stringValue(legacyMetadata.get(LEGACY_KEY_NAME)));
        String description = firstNonEmpty(
                readDescriptionFromArchive(apkFile),
                stringValue(legacyMetadata.get(LEGACY_KEY_DESCRIPTION)));

        int minApiVersion = readInt(
                moduleProperties,
                KEY_MIN_API_VERSION,
                legacyMetadata.get(LEGACY_KEY_MIN_API_VERSION),
                0);
        int targetApiVersion = readInt(
                moduleProperties,
                KEY_TARGET_API_VERSION,
                legacyMetadata.get(LEGACY_KEY_TARGET_API_VERSION),
                minApiVersion);
        boolean staticScope = readBoolean(
                moduleProperties,
                KEY_STATIC_SCOPE,
                legacyMetadata.get(LEGACY_KEY_STATIC_SCOPE),
                false);
        String author = firstNonEmpty(
                readString(moduleProperties, KEY_AUTHOR),
                stringValue(legacyMetadata.get(LEGACY_KEY_AUTHOR)));
        String version = firstNonEmpty(
                readString(moduleProperties, KEY_VERSION),
                stringValue(legacyMetadata.get(LEGACY_KEY_VERSION)));

        boolean hasModernEntrypoint = !modernJavaInitList.isEmpty() || !modernNativeInitList.isEmpty();
        boolean hasLegacyEntrypoint = !legacyJavaInitList.isEmpty() || !legacyNativeInitList.isEmpty();
        ModulePipeline pipeline =
                versionRouter.determinePipeline(
                        minApiVersion,
                        targetApiVersion,
                        hasModernEntrypoint,
                        hasLegacyEntrypoint);

        List<String> javaInitList = new ArrayList<>();
        List<String> nativeInitList = new ArrayList<>();
        if (pipeline == ModulePipeline.MODERN) {
            javaInitList.addAll(modernJavaInitList);
            nativeInitList.addAll(modernNativeInitList);
        } else if (pipeline == ModulePipeline.LEGACY) {
            javaInitList.addAll(legacyJavaInitList);
            nativeInitList.addAll(legacyNativeInitList);
        }

        if (pipeline != ModulePipeline.MODERN && scopes.isEmpty()) {
            scopes.addAll(readLegacyList(legacyMetadata.get(LEGACY_KEY_SCOPES)));
        }

        return new ModuleMetadata(
                packageName,
                minApiVersion,
                targetApiVersion,
                staticScope,
                author,
                version,
                name,
                description,
                scopes,
                javaInitList,
                nativeInitList,
                pipeline);
    }

    private Map<String, Object> readLegacyMetadata(File apkFile) {
        try {
            return VectorMetaDataReader.getMetaData(apkFile);
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    private String readPackageName(File apkFile, Map<String, Object> legacyMetadata) {
        if (packageManager != null) {
            try {
                PackageInfo packageInfo = packageManager.getPackageArchiveInfo(
                        apkFile.getAbsolutePath(),
                        PackageManager.GET_META_DATA);
                if (packageInfo != null && packageInfo.packageName != null) {
                    return packageInfo.packageName;
                }
            } catch (Throwable ignored) {
            }
        }
        return stringValue(legacyMetadata.get(LEGACY_KEY_PACKAGE_NAME));
    }

    private String readLabelFromArchive(File apkFile) {
        if (packageManager == null) {
            return null;
        }
        try {
            PackageInfo packageInfo = packageManager.getPackageArchiveInfo(
                    apkFile.getAbsolutePath(),
                    PackageManager.GET_META_DATA);
            if (packageInfo == null || packageInfo.applicationInfo == null) {
                return null;
            }
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            applicationInfo.sourceDir = apkFile.getAbsolutePath();
            applicationInfo.publicSourceDir = apkFile.getAbsolutePath();
            CharSequence label = applicationInfo.loadLabel(packageManager);
            return label == null ? null : label.toString().trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readDescriptionFromArchive(File apkFile) {
        if (packageManager == null) {
            return null;
        }
        try {
            PackageInfo packageInfo = packageManager.getPackageArchiveInfo(
                    apkFile.getAbsolutePath(),
                    PackageManager.GET_META_DATA);
            if (packageInfo == null || packageInfo.applicationInfo == null) {
                return null;
            }
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            applicationInfo.sourceDir = apkFile.getAbsolutePath();
            applicationInfo.publicSourceDir = apkFile.getAbsolutePath();
            CharSequence description = applicationInfo.loadDescription(packageManager);
            return description == null ? null : description.toString().trim();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void loadProperties(ZipFile zipFile, Properties properties) throws IOException {
        ZipEntry entry = zipFile.getEntry(MODERN_MODULE_PROP);
        if (entry == null) {
            return;
        }
        try (InputStream is = zipFile.getInputStream(entry)) {
            properties.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
    }

    private List<String> readList(ZipFile zipFile, String entryName) throws IOException {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                result.add(value);
            }
        }
        return result;
    }

    private List<String> readLegacyList(Object rawValue) {
        String value = stringValue(rawValue);
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> deduplicated = new LinkedHashSet<>();
        for (String token : value.split("[\\s,;]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                deduplicated.add(trimmed);
            }
        }
        return new ArrayList<>(deduplicated);
    }

    private String readString(Properties properties, String key) {
        if (properties == null) {
            return null;
        }
        String value = properties.getProperty(key);
        return trimToNull(value);
    }

    private int readInt(Properties properties, String modernKey, Object legacyValue, int defaultValue) {
        String modernValue = readString(properties, modernKey);
        Integer parsed = parseInt(modernValue);
        if (parsed != null) {
            return parsed;
        }
        parsed = parseInt(stringValue(legacyValue));
        return parsed != null ? parsed : defaultValue;
    }

    private boolean readBoolean(Properties properties, String modernKey, Object legacyValue, boolean defaultValue) {
        String modernValue = readString(properties, modernKey);
        if (modernValue != null) {
            return Boolean.parseBoolean(modernValue);
        }
        String legacyValueString = stringValue(legacyValue);
        if (legacyValueString != null) {
            return Boolean.parseBoolean(legacyValueString);
        }
        return defaultValue;
    }

    private Integer parseInt(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return parseLeadingInt(trimmed);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer parseLeadingInt(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            throw new NumberFormatException("No leading digits: " + value);
        }
        return Integer.parseInt(value.substring(0, end));
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
