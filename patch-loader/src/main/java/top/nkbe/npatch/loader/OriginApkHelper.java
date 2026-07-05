package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import top.nkbe.npatch.loader.util.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

    public static Path prepareOriginApk(ApplicationInfo appInfo, ClassLoader baseClassLoader) throws IOException {
        Path internalOriginDir = Paths.get(appInfo.dataDir, "cache/code_cache/");
        long sourceCrc = getOriginalApkCrc(appInfo.sourceDir);

        Path internalCacheApk = internalOriginDir.resolve(sourceCrc + ".apk");

        if (!Files.exists(internalOriginDir)) {
            Files.createDirectories(internalOriginDir);
        }

        if (!Files.exists(internalCacheApk)) {
            Log.i(TAG, "Extracting origin.apk from assets.");
            FileUtils.deleteFolderIfExists(internalOriginDir);
            Files.createDirectories(internalOriginDir);

            try (InputStream is = baseClassLoader.getResourceAsStream(ORIGINAL_APK_ASSET_PATH)) {
                if (is == null) throw new IOException("Original APK not found in assets");
                Files.copy(is, internalCacheApk);
            }
        } else {
            Log.d(TAG, "Internal cache hit: " + internalCacheApk);
        }

        try {
            internalCacheApk.toFile().setWritable(false);
        } catch (Exception ignored) {
        }

        return internalCacheApk;
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
