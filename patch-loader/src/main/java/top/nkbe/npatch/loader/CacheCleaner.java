package top.nkbe.npatch.loader;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class CacheCleaner {

    private static final String TAG = "NPatch-Cache";
    private static final String STAMP_FILE_NAME = ".npatch_patch_stamp";

    public static boolean handlePatchUpgrade(ApplicationInfo appInfo, String patchedApkPath) {
        if (appInfo == null || appInfo.dataDir == null || patchedApkPath == null) {
            return false;
        }

        File cacheRoot = new File(appInfo.dataDir, "cache");
        File stampFile = new File(cacheRoot, STAMP_FILE_NAME);

        String currentStamp = computeStamp(patchedApkPath);
        if (currentStamp == null) return false;

        String previousStamp = readStamp(stampFile);
        if (currentStamp.equals(previousStamp)) {
            return false;
        }

        // Wipe the entire cache directory. For simplicity we don't selectively delete,
        // we wipe everything inside it, including the stamp file.
        // In Android, getCacheDir() returns the cache directory of the app.
        if (cacheRoot.exists() && cacheRoot.isDirectory()) {
            wipeAll(cacheRoot);
        }

        writeStamp(stampFile, currentStamp);
        return previousStamp != null;
    }

    public static void sweepOriginApkCache(ApplicationInfo appInfo, long currentCrc) {
        if (appInfo == null || appInfo.dataDir == null) return;

        File codeCache = new File(appInfo.dataDir, "cache/code_cache");
        File[] children = codeCache.listFiles();
        if (children == null) return;

        String keepName = currentCrc + ".apk";

        Arrays.stream(children)
                .filter(File::isFile)
                .filter(f -> f.getName().endsWith(".apk"))
                .filter(f -> !f.getName().equals(keepName))
                .forEach(f -> {
                    if (!f.delete()) {
                        Log.w(TAG, "Failed to delete stale origin apk: " + f);
                    }
                });
    }

    /**
     * Removes stale libnpatch-*.so temp files left in cache/ by meta-loader.
     * Keeps the newest one because the current process has it loaded via System.load().
     */
    public static void sweepLibNpatchCache(ApplicationInfo appInfo) {
        if (appInfo == null || appInfo.dataDir == null) return;

        File cacheDir = new File(appInfo.dataDir, "cache");
        File[] children = cacheDir.listFiles((dir, name) ->
                name.startsWith("libnpatch-") && name.endsWith(".so"));
        if (children == null || children.length <= 1) return;

        File newest = children[0];
        for (File f : children) {
            if (f.lastModified() > newest.lastModified()) newest = f;
        }
        final File keep = newest;
        Arrays.stream(children)
                .filter(f -> !f.equals(keep))
                .forEach(f -> {
                    if (!f.delete()) {
                        Log.w(TAG, "Failed to delete stale libnpatch: " + f);
                    }
                });
    }

    public static void sweepLegacyNpatchCache(ApplicationInfo appInfo) {
        if (appInfo == null || appInfo.dataDir == null) return;
        deleteRecursive(new File(appInfo.dataDir, "cache/npatch"));
    }

    public static void sweepModuleNativeCache(ApplicationInfo appInfo, Map<String, String> activeModuleApkPaths) {
        if (appInfo == null || appInfo.dataDir == null) return;

        Map<String, String> activeDirToStamp = new HashMap<>();
        if (activeModuleApkPaths != null) {
            activeModuleApkPaths.forEach((key, value) -> {
                if (key != null && value != null) {
                    File apk = new File(value);
                    String stamp = ModuleNativeCache.stamp(apk);
                    activeDirToStamp.put(ModuleNativeCache.moduleDirectoryName(key), stamp);
                }
            });
        }

        File cacheRoot = new File(appInfo.dataDir, "cache");
        File nativeRoot = new File(cacheRoot, "native");
        sweepModuleNativeRoot(ModuleNativeCache.root(cacheRoot), activeDirToStamp);
        sweepLegacyModuleNativeRoot(nativeRoot);
        sweepLegacyNpatchCache(appInfo);
    }

    private static void sweepModuleNativeRoot(File nativeRoot, Map<String, String> activeDirToStamp) {
        File[] moduleDirs = nativeRoot.listFiles();
        if (moduleDirs == null) return;

        for (File moduleDir : moduleDirs) {
            if (!moduleDir.isDirectory()) {
                deleteRecursive(moduleDir);
                continue;
            }

            String activeStamp = activeDirToStamp.get(moduleDir.getName());
            if (activeStamp == null) {
                deleteRecursive(moduleDir);
                continue;
            }

            File[] stampDirs = moduleDir.listFiles();
            if (stampDirs != null) {
                Arrays.stream(stampDirs)
                        .filter(s -> !s.getName().equals(activeStamp))
                        .forEach(CacheCleaner::deleteRecursive);
            }
        }
    }

    private static void sweepLegacyModuleNativeRoot(File nativeRoot) {
        File[] entries = nativeRoot.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            if ("host".equals(name) || "modules".equals(name)) {
                continue;
            }
            deleteRecursive(entry);
        }
    }

    private static void wipeAll(File cacheRoot) {
        File codeCache = new File(cacheRoot, "code_cache");
        File[] children = codeCache.listFiles();

        if (children != null) {
            Arrays.stream(children)
                    .filter(File::isFile)
                    .filter(f -> f.getName().endsWith(".apk"))
                    .forEach(File::delete);
        }

        deleteRecursive(new File(codeCache, "native"));
        deleteRecursive(new File(codeCache, "mods"));
        deleteRecursive(new File(cacheRoot, "native"));
        deleteRecursive(new File(cacheRoot, "npatch"));

        // Sweep all but the newest libnpatch-*.so (current process has it mmaped).
        File[] libs = cacheRoot.listFiles((dir, name) ->
                name.startsWith("libnpatch-") && name.endsWith(".so"));
        if (libs != null && libs.length > 1) {
            File newest = libs[0];
            for (File f : libs) {
                if (f.lastModified() > newest.lastModified()) newest = f;
            }
            for (File f : libs) {
                if (!f.equals(newest)) f.delete();
            }
        }
    }

    private static String computeStamp(String path) {
        File f = new File(path);
        return f.exists() ? (f.lastModified() + "-" + f.length()) : null;
    }

    private static String readStamp(File f) {
        if (!f.exists()) return null;
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void writeStamp(File f, String stamp) {
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Files.write(f.toPath(), stamp.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Failed to write cache stamp", e);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                Arrays.stream(children).forEach(CacheCleaner::deleteRecursive);
            }
        }
        
        if (!f.delete()) {
            Log.w(TAG, "Failed to delete: " + f);
        }
    }
}
