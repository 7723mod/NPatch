package top.nkbe.npatch.loader;

import android.app.Application;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import org.matrix.vector.ipc.LoadedModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModuleNativeCache {
    private static final String TAG = "NPatch-NativeCache";
    private static final String READY_FILE = ".ready";

    private ModuleNativeCache() {}

    static File root(File cacheDir) {
        return new File(new File(cacheDir, "native"), "modules");
    }

    static String moduleDirectoryName(String packageName) {
        return packageName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static String stamp(File apk) {
        return apk.lastModified() + "-" + apk.length();
    }

    static synchronized File prepare(Application app, LoadedModule LoadedModule) {
        if (app == null || LoadedModule == null || LoadedModule.packageName == null || LoadedModule.apkPath == null) {
            return null;
        }

        File apk = new File(LoadedModule.apkPath);
        if (!apk.isFile()) {
            Log.e(TAG, "LoadedModule APK is unavailable: " + LoadedModule.apkPath);
            return null;
        }

        File moduleRoot = new File(root(app.getCacheDir()), moduleDirectoryName(LoadedModule.packageName));
        String stamp = stamp(apk);
        File target = new File(moduleRoot, stamp);
        if (isReady(target)) {
            return target;
        }

        File staging = new File(moduleRoot,
                stamp + ".tmp-" + Process.myPid() + "-" + Thread.currentThread().getId());
        deleteRecursive(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            Log.e(TAG, "Unable to create native staging directory: " + staging);
            return null;
        }

        try {
            boolean extracted = extractPreferredAbi(apk, staging);
            if (!extracted) {
                deleteRecursive(staging);
                return null;
            }

            Files.write(new File(staging, READY_FILE).toPath(),
                    stamp.getBytes(StandardCharsets.UTF_8));
            deleteRecursive(target);
            if (!staging.renameTo(target)) {
                throw new IOException("Unable to publish native cache: " + target);
            }
            Log.i(TAG, "Prepared LoadedModule native cache: " + target);
            return target;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to prepare native cache for " + LoadedModule.packageName, e);
            deleteRecursive(staging);
            return null;
        }
    }

    private static boolean extractPreferredAbi(File apk, File staging) throws IOException {
        String[] abis = Process.is64Bit()
                ? Build.SUPPORTED_64_BIT_ABIS
                : Build.SUPPORTED_32_BIT_ABIS;

        try (ZipFile zip = new ZipFile(apk)) {
            for (String abi : abis) {
                String prefix = "lib/" + abi + "/";
                boolean extracted = false;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".so")) {
                        continue;
                    }

                    String fileName = new File(name).getName();
                    if (fileName.isEmpty()) continue;
                    File output = new File(staging, fileName);
                    File temporary = new File(staging, fileName + ".tmp");
                    try (InputStream input = zip.getInputStream(entry);
                         FileOutputStream stream = new FileOutputStream(temporary)) {
                        transfer(input, stream);
                        stream.getFD().sync();
                    }
                    if (!temporary.renameTo(output)) {
                        throw new IOException("Unable to publish native library: " + output);
                    }
                    output.setReadable(true, true);
                    output.setExecutable(true, true);
                    extracted = true;
                }
                if (extracted) return true;
            }
        }
        return false;
    }

    private static boolean isReady(File directory) {
        if (!directory.isDirectory() || !new File(directory, READY_FILE).isFile()) return false;
        File[] libraries = directory.listFiles((dir, name) -> name.endsWith(".so"));
        return libraries != null && libraries.length > 0;
    }

    private static void transfer(InputStream input, FileOutputStream output) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        if (!file.delete()) Log.w(TAG, "Unable to delete stale cache entry: " + file);
    }
}
