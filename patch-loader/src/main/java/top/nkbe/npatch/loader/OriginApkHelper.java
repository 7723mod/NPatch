package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import top.nkbe.npatch.loader.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class OriginApkHelper {

    private static final String TAG = "NPatch-ApkHelper";
    private static final int PER_USER_RANGE = 100000;
    private static final String NATIVE_CACHE_COMPLETE = ".complete";

    private static final long LOCK_TIMEOUT_MS = 10_000L;
    private static final long LOCK_POLL_INTERVAL_MS = 50L;
    private static final String VERIFIED_SUFFIX = ".verified";

    public static Path prepareOriginApk(ApplicationInfo appInfo, ClassLoader baseClassLoader) throws IOException {
        Path internalOriginDir = Paths.get(appInfo.dataDir, "cache/code_cache/");
        long sourceCrc = getOriginalApkCrc(appInfo.sourceDir);
        long expectedSize = resolveExpectedOriginApkSize(appInfo);

        Path internalCacheApk = internalOriginDir.resolve(sourceCrc + ".apk");

        if (!Files.exists(internalOriginDir)) {
            Files.createDirectories(internalOriginDir);
        }

        // Fast-path: if valid, avoid locking entirely
        if (isApkValid(internalCacheApk, expectedSize)) {
            Log.d(TAG, "Internal cache hit: " + internalCacheApk);
            return internalCacheApk;
        }

        Path lockFile = internalOriginDir.resolve("origin_apk.lock");
        try (FileChannel lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            FileLock lock = null;
            long deadline = SystemClock.elapsedRealtime() + LOCK_TIMEOUT_MS;
            while (lock == null) {
                try {
                    lock = lockChannel.tryLock();
                } catch (IOException e) {
                    Log.w(TAG, "tryLock threw exception, proceeding without lock", e);
                    break;
                }
                if (lock == null) {
                    if (SystemClock.elapsedRealtime() >= deadline) {
                        Log.w(TAG, "origin.apk lock timeout, proceeding without lock (degraded mode)");
                        break;
                    }
                    try {
                        Thread.sleep(LOCK_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            try {
                // Double-check: another process might have finished extracting while waiting for the lock
                if (isApkValid(internalCacheApk, expectedSize)) {
                    Log.d(TAG, "Internal cache hit after lock: " + internalCacheApk);
                    return internalCacheApk;
                }

                Log.i(TAG, "Extracting origin.apk from assets.");
                // Without the lock another process may be extracting concurrently; do not touch its
                // temp files or the shared target. ATOMIC_MOVE below replaces the target safely.
                if (lock != null) {
                    cleanupOrphanTempFiles(internalOriginDir, sourceCrc);
                    Files.deleteIfExists(getVerifiedSidecarPath(internalCacheApk));
                }

                Path tempFile = internalOriginDir.resolve(sourceCrc + ".tmp." + Process.myPid() + "." + System.currentTimeMillis());
                try {
                    try (InputStream is = baseClassLoader.getResourceAsStream(ORIGINAL_APK_ASSET_PATH)) {
                        if (is == null) throw new IOException("Original APK not found in assets");
                        try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                            byte[] buffer = new byte[16384];
                            int len;
                            while ((len = is.read(buffer)) > 0) {
                                fos.write(buffer, 0, len);
                            }
                            fos.flush();
                            fos.getFD().sync();
                        }
                    }

                    try {
                        Files.move(tempFile, internalCacheApk, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Log.w(TAG, "ATOMIC_MOVE unsupported on this filesystem, falling back to copy+delete", e);
                        Files.copy(tempFile, internalCacheApk, StandardCopyOption.REPLACE_EXISTING);
                    }

                    // Perform verification and record sidecar
                    isApkValid(internalCacheApk, expectedSize);
                } finally {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                    }
                }
            } finally {
                if (lock != null) {
                    try {
                        lock.release();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        return internalCacheApk;
    }

    public static long resolveExpectedOriginApkSize(ApplicationInfo appInfo) {
        if (appInfo == null || appInfo.sourceDir == null) return -1L;
        try (ZipFile selfApk = new ZipFile(appInfo.sourceDir)) {
            ZipEntry entry = selfApk.getEntry(ORIGINAL_APK_ASSET_PATH);
            if (entry == null) {
                Log.w(TAG, "origin.apk asset entry not found in self APK, size check disabled");
                return -1L;
            }
            return entry.getSize();
        } catch (IOException e) {
            Log.w(TAG, "Failed to resolve expected origin.apk size", e);
            return -1L;
        }
    }

    private static boolean isApkValid(Path apkPath, long expectedSize) {
        if (!Files.isRegularFile(apkPath)) return false;
        Path verifiedPath = getVerifiedSidecarPath(apkPath);
        try {
            long actualSize = Files.size(apkPath);
            if (actualSize <= 0) {
                Files.deleteIfExists(verifiedPath);
                return false;
            }
            if (expectedSize > 0 && actualSize != expectedSize) {
                Log.w(TAG, "Cache apk size mismatch: actual " + actualSize + ", expected " + expectedSize);
                Files.deleteIfExists(verifiedPath);
                return false;
            }

            long mtime = Files.getLastModifiedTime(apkPath).toMillis();
            String expectedStamp = mtime + ":" + actualSize;

            if (Files.isRegularFile(verifiedPath)) {
                try {
                    String recordedStamp = new String(Files.readAllBytes(verifiedPath), StandardCharsets.UTF_8).trim();
                    if (expectedStamp.equals(recordedStamp)) {
                        return true;
                    }
                } catch (IOException ignored) {
                }
            }

            // Fallback to ZipFile verification
            try (ZipFile zip = new ZipFile(apkPath.toFile())) {
                if (zip.getEntry("AndroidManifest.xml") == null) {
                    Log.w(TAG, "Cache apk missing AndroidManifest.xml: " + apkPath);
                    Files.deleteIfExists(verifiedPath);
                    return false;
                }
            }

            // Write or update sidecar verified file
            try {
                Files.write(verifiedPath, expectedStamp.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException e) {
                Log.w(TAG, "Failed to write verified sidecar for " + apkPath, e);
            }

            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Failed to validate apk: " + apkPath, e);
            try {
                Files.deleteIfExists(verifiedPath);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    private static Path getVerifiedSidecarPath(Path apkPath) {
        return Paths.get(apkPath.toString() + VERIFIED_SUFFIX);
    }

    private static void cleanupOrphanTempFiles(Path dir, long sourceCrc) {
        String prefix = sourceCrc + ".tmp.";
        // Another process (possibly in lockless degraded mode) may be writing a temp right now.
        // Only reap temps old enough that no in-flight extraction could still own them.
        long staleBefore = System.currentTimeMillis() - LOCK_TIMEOUT_MS;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, prefix + "*")) {
            for (Path orphan : stream) {
                try {
                    if (Files.getLastModifiedTime(orphan).toMillis() < staleBefore) {
                        Files.deleteIfExists(orphan);
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static Path prepareNativeLibraryDir(ApplicationInfo appInfo, Path originApkPath, String patchedApkPath) throws IOException {
        Path nativeRoot = Paths.get(appInfo.dataDir, "cache/native/host/");
        List<String> apkPaths = new ArrayList<>();
        apkPaths.add(originApkPath.toString());
        if (appInfo.splitSourceDirs != null) {
            for (String splitSourceDir : appInfo.splitSourceDirs) {
                if (splitSourceDir != null && !splitSourceDir.isEmpty()) {
                    apkPaths.add(splitSourceDir);
                }
            }
        }
        if (patchedApkPath != null
                && !patchedApkPath.isEmpty()
                && !patchedApkPath.equals(originApkPath.toString())) {
            apkPaths.add(patchedApkPath);
        }

        String stamp = buildNativeLibraryStamp(apkPaths);
        Path targetDir = nativeRoot.resolve(stamp);
        if (hasNativeLibraries(targetDir)) {
            return targetDir;
        }

        // Several app processes may bootstrap at once. Serialize extraction so no process can
        // observe a directory after only the first library has been written and permanently
        // mistake that partial cache for a complete one.
        Path nativeCacheDir = nativeRoot.getParent();
        Files.createDirectories(nativeCacheDir);
        Path lockPath = nativeCacheDir.resolve("host.lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath,
                                                        StandardOpenOption.CREATE,
                                                        StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {
            if (hasNativeLibraries(targetDir)) {
                return targetDir;
            }

            FileUtils.deleteFolderIfExists(nativeRoot);
            Files.createDirectories(targetDir);

            String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            for (String abi : abis) {
                boolean extractedAny = false;
                for (String apkPath : apkPaths) {
                    extractedAny |= extractNativeLibrariesForAbi(apkPath, abi, targetDir);
                }
                if (extractedAny) {
                    makeNativeLibrariesReadOnly(targetDir);
                    Files.write(targetDir.resolve(NATIVE_CACHE_COMPLETE), new byte[0],
                                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    Log.i(TAG, "Prepared native libraries for " + abi + " at " + targetDir);
                    return targetDir;
                }
            }

            FileUtils.deleteFolderIfExists(targetDir);
            return null;
        }
    }

    public static long getOriginalApkCrc(String sourceDir) throws IOException {
        try (ZipFile sourceFile = new ZipFile(sourceDir)) {
            ZipEntry entry = sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH);
            if (entry == null) {
                return 0;
            }
            return entry.getCrc();
        }
    }

    private static String buildNativeLibraryStamp(List<String> apkPaths) {
        StringBuilder stamp = new StringBuilder();
        for (String apkPath : apkPaths) {
            File file = new File(apkPath);
            if (stamp.length() > 0) {
                stamp.append('_');
            }
            stamp.append(Math.abs(apkPath.hashCode()))
                    .append('-')
                    .append(file.lastModified())
                    .append('-')
                    .append(file.length());
        }
        return stamp.toString();
    }

    private static boolean hasNativeLibraries(Path dir) {
        if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve(NATIVE_CACHE_COMPLETE))) {
            return false;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.so")) {
            return stream.iterator().hasNext();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean extractNativeLibrariesForAbi(String apkPath, String abi, Path targetDir) {
        boolean extractedAny = false;
        String prefix = "lib/" + abi + "/";
        try (ZipFile apk = new ZipFile(apkPath)) {
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".so")) {
                    continue;
                }

                Path target = targetDir.resolve(new File(name).getName());
                try (InputStream in = apk.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(target.toFile())) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                extractedAny = true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to extract native libraries from " + apkPath, e);
        }
        return extractedAny;
    }

    private static void makeNativeLibrariesReadOnly(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.so")) {
            for (Path lib : stream) {
                File file = lib.toFile();
                // Keep dlopen targets immutable after extraction; writable native code trips some runtimes.
                file.setReadable(true, false);
                file.setExecutable(true, false);
                file.setWritable(false, false);
            }
        } catch (IOException ignored) {
        }
    }
}
