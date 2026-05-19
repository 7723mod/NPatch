package top.nkbe.npatch.util;

import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import org.lsposed.lspd.models.PreLoadedApk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipFile;

public class ModuleLoader {

    private static final String TAG = "NPatch";
    private static final String MODERN_JAVA_INIT = "META-INF/xposed/java_init.list";
    private static final String MODERN_NATIVE_INIT = "META-INF/xposed/native_init.list";
    private static final String MODERN_MODULE_PROP = "META-INF/xposed/module.prop";
    private static final String LEGACY_JAVA_INIT = "assets/xposed_init";
    private static final String LEGACY_NATIVE_INIT = "assets/native_init";
    private static final int FRAMEWORK_API_VERSION = 101;
    private static final int MODERN_TARGET_API_VERSION = 101;
    private static final int LEGACY_MAX_API_VERSION = 94;

    private enum ModulePipeline {
        LEGACY,
        MODERN,
        UNSUPPORTED
    }

    private static void readDexes(ZipFile apkFile, List<SharedMemory> preLoadedDexes) {
        int secondary = 2;
        for (var dexFile = apkFile.getEntry("classes.dex"); dexFile != null;
             dexFile = apkFile.getEntry("classes" + secondary + ".dex"), secondary++) {
            SharedMemory memory = null;
            try (var in = apkFile.getInputStream(dexFile)) {
                int dexSize = Math.toIntExact(dexFile.getSize());
                if (dexSize <= 0) {
                    Log.w(TAG, "Invalid dex size for " + dexFile + " in " + apkFile);
                    continue;
                }
                memory = SharedMemory.create(null, dexSize);
                var byteBuffer = memory.mapReadWrite();
                try (ReadableByteChannel channel = Channels.newChannel(in)) {
                    readFully(channel, byteBuffer, dexFile.getName());
                } finally {
                    SharedMemory.unmap(byteBuffer);
                }
                memory.setProtect(OsConstants.PROT_READ);
                preLoadedDexes.add(memory);
                memory = null;
            } catch (IOException | ErrnoException | ArithmeticException e) {
                if (memory != null) {
                    memory.close();
                }
                Log.w(TAG, "Can not load " + dexFile + " in " + apkFile, e);
            }
        }
    }

    private static void readFully(ReadableByteChannel channel, ByteBuffer buffer, String name)
            throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IOException("Unexpected EOF while reading " + name);
            }
        }
    }

    private static void readName(ZipFile apkFile, String initName, List<String> names) {
        var initEntry = apkFile.getEntry(initName);
        if (initEntry == null) return;
        try (var in = apkFile.getInputStream(initEntry)) {
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String name;
            while ((name = reader.readLine()) != null) {
                name = name.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                names.add(name);
            }
        } catch (IOException e) {
            Log.e(TAG, "Can not open " + initEntry, e);
        }
    }

    private static final class ApiVersions {
        final int minApiVersion;
        final int targetApiVersion;

        ApiVersions(int minApiVersion, int targetApiVersion) {
            this.minApiVersion = minApiVersion;
            this.targetApiVersion = targetApiVersion;
        }
    }

    private static ApiVersions readApiVersions(ZipFile apkFile, int fallbackMinApiVersion) {
        var entry = apkFile.getEntry(MODERN_MODULE_PROP);
        if (entry == null) return new ApiVersions(fallbackMinApiVersion, fallbackMinApiVersion);
        var properties = new Properties();
        try (var in = apkFile.getInputStream(entry)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            var minApiVersion = readInt(properties, "minApiVersion", fallbackMinApiVersion);
            var targetApiVersion = readInt(properties, "targetApiVersion", minApiVersion);
            return new ApiVersions(minApiVersion, targetApiVersion);
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "Can not read " + MODERN_MODULE_PROP + " in " + apkFile, e);
            return new ApiVersions(fallbackMinApiVersion, fallbackMinApiVersion);
        }
    }

    private static int readInt(Properties properties, String key, int fallback) {
        var value = properties.getProperty(key);
        var parsed = parseLeadingInt(value);
        return parsed == null ? fallback : parsed;
    }

    private static Integer parseLeadingInt(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int end = 0;
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return null;
        }
        return Integer.parseInt(trimmed.substring(0, end));
    }

    private static ModulePipeline determinePipeline(ZipFile apkFile, int fallbackMinApiVersion) {
        var apiVersions = readApiVersions(apkFile, fallbackMinApiVersion);
        var hasModernEntry =
                apkFile.getEntry(MODERN_JAVA_INIT) != null
                        || apkFile.getEntry(MODERN_NATIVE_INIT) != null;
        var hasLegacyEntry =
                apkFile.getEntry(LEGACY_JAVA_INIT) != null
                        || apkFile.getEntry(LEGACY_NATIVE_INIT) != null;

        if (hasModernEntry
                && apiVersions.minApiVersion <= FRAMEWORK_API_VERSION
                && apiVersions.targetApiVersion >= MODERN_TARGET_API_VERSION) {
            return ModulePipeline.MODERN;
        }
        if (hasLegacyEntry && apiVersions.minApiVersion <= LEGACY_MAX_API_VERSION) {
            return ModulePipeline.LEGACY;
        }
        return ModulePipeline.UNSUPPORTED;
    }

    public static PreLoadedApk loadModule(String path) {
        return loadModule(path, 0);
    }

    public static PreLoadedApk loadModule(String path, int fallbackMinApiVersion) {
        if (path == null) return null;
        var file = new PreLoadedApk();
        var preLoadedDexes = new ArrayList<SharedMemory>();
        var moduleClassNames = new ArrayList<String>(1);
        var moduleLibraryNames = new ArrayList<String>(1);
        boolean isLegacy = false;
        try (var apkFile = new ZipFile(path)) {
            var pipeline = determinePipeline(apkFile, fallbackMinApiVersion);
            if (pipeline == ModulePipeline.UNSUPPORTED) {
                Log.w(TAG, "Unsupported Xposed module API or missing init entries: " + path);
                return null;
            }

            readDexes(apkFile, preLoadedDexes);
            if (pipeline == ModulePipeline.MODERN) {
                readName(apkFile, MODERN_JAVA_INIT, moduleClassNames);
                readName(apkFile, MODERN_NATIVE_INIT, moduleLibraryNames);
            } else {
                isLegacy = true;
                readName(apkFile, LEGACY_JAVA_INIT, moduleClassNames);
                readName(apkFile, LEGACY_NATIVE_INIT, moduleLibraryNames);
            }
        } catch (IOException e) {
            Log.e(TAG, "Can not open " + path, e);
            return null;
        }
        if (preLoadedDexes.isEmpty() && moduleLibraryNames.isEmpty()) return null;
        if (moduleClassNames.isEmpty() && moduleLibraryNames.isEmpty()) return null;
        file.preLoadedDexes = preLoadedDexes;
        file.moduleClassNames = moduleClassNames;
        file.moduleLibraryNames = moduleLibraryNames;
        file.legacy = isLegacy;
        return file;
    }
}
