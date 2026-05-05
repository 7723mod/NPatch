package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import top.nkbe.npatch.loader.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class OriginApkHelper {

    private static final String TAG = "NPatch-ApkHelper";
    private static final int PER_USER_RANGE = 100000;

    public static Path prepareOriginApk(ApplicationInfo appInfo, ClassLoader baseClassLoader) throws IOException {
        Path internalOriginDir = Paths.get(appInfo.dataDir, "cache/npatch/origin/");
        long sourceCrc = getOriginalApkCrc(appInfo.sourceDir);

        Path internalCacheApk = internalOriginDir.resolve(sourceCrc + ".apk");

        int userId = appInfo.uid / PER_USER_RANGE;
        Path externalOriginPath = Paths.get("/storage/emulated/" + userId + "/Android/data/" + appInfo.packageName + "/cache/npatch/origin/origin.apk");

        Log.d(TAG, "Checking external APK at: " + externalOriginPath);

        if (!Files.exists(internalOriginDir)) {
            Files.createDirectories(internalOriginDir);
        }

        boolean externalExists = Files.exists(externalOriginPath);

        if (externalExists) {
            Log.i(TAG, "External origin.apk found! Overwriting internal cache.");
            try (InputStream in = Files.newInputStream(externalOriginPath)) {
                Files.copy(in, internalCacheApk, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
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
        }

        try {
            internalCacheApk.toFile().setWritable(false);
        } catch (Exception ignored) {
        }

        return internalCacheApk;
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
}
