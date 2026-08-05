package top.nkbe.npatch.service;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import top.nkbe.npatch.loader.util.FileUtils;
import top.nkbe.npatch.share.Constants;
import top.nkbe.npatch.util.LocalInjectedModuleService;
import top.nkbe.npatch.util.ModuleLoader;
import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.ipc.IProcessChannel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class IntegrApplicationService extends IFrameworkService.Stub {

    private static final String TAG = "NPatch";

    private final List<LoadedModule> legacyModules = new ArrayList<>();
    private final List<LoadedModule> modernModules = new ArrayList<>();

    public IntegrApplicationService(Context context) {
        try {
            String[] assetsList = context.getAssets().list("npatch/modules");
            if (assetsList == null || assetsList.length == 0) {
                return;
            }
            for (var name : assetsList) {
                if (name == null || name.length() <= 4) continue;

                String packageName = name.substring(0, name.length() - 4);
                String modulePath = context.getCacheDir() + "/code_cache/mods/" + packageName + "/";
                String cacheApkPath;

                try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
                    ZipEntry entry = sourceFile.getEntry(Constants.EMBEDDED_MODULES_ASSET_PATH + name);
                    if (entry == null) {
                        Log.w(TAG, "Skipping LoadedModule (entry not found in APK): " + name);
                        continue;
                    }
                    cacheApkPath = modulePath + entry.getCrc() + ".apk";
                }

                if (!Files.exists(Paths.get(cacheApkPath))) {
                    Log.i(TAG, "Extracting embedded LoadedModule: " + packageName);
                    FileUtils.deleteFolderIfExists(Paths.get(modulePath));
                    Files.createDirectories(Paths.get(modulePath));
                    try (var is = context.getAssets().open("npatch/modules/" + name)) {
                        Files.copy(is, Paths.get(cacheApkPath));
                    }
                }

                var LoadedModule = new LoadedModule();
                LoadedModule.apkPath = cacheApkPath;
                LoadedModule.packageName = packageName;
                LoadedModule.applicationInfo = readApplicationInfo(context, cacheApkPath, packageName);
                var parsedModule = ModuleLoader.loadModule(
                        cacheApkPath,
                        readLegacyMinApiVersion(LoadedModule.applicationInfo));
                LoadedModule.code = parsedModule == null ? null : parsedModule.code;
                if (LoadedModule.code == null) {
                    Log.w(TAG, "Skipping unsupported or unreadable embedded LoadedModule: " + packageName);
                    continue;
                }
                LoadedModule.appId = LoadedModule.applicationInfo == null ? -1 : LoadedModule.applicationInfo.uid;
                LoadedModule.service = new LocalInjectedModuleService(context, LoadedModule.packageName);
                if (LoadedModule.code != null && LoadedModule.code.legacy) {
                    legacyModules.add(LoadedModule);
                } else {
                    modernModules.add(LoadedModule);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when initializing IntegrApplicationServiceClient", e);
        }
    }

    private static ApplicationInfo readApplicationInfo(Context context, String apkPath, String fallbackPackageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            PackageInfo packageInfo = packageManager.getPackageArchiveInfo(apkPath, PackageManager.GET_META_DATA);
            if (packageInfo != null && packageInfo.applicationInfo != null) {
                ApplicationInfo applicationInfo = packageInfo.applicationInfo;
                applicationInfo.sourceDir = apkPath;
                applicationInfo.publicSourceDir = apkPath;
                if (applicationInfo.packageName == null) {
                    applicationInfo.packageName = packageInfo.packageName;
                }
                return applicationInfo;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read embedded LoadedModule ApplicationInfo: " + fallbackPackageName, e);
        }
        ApplicationInfo fallback = new ApplicationInfo();
        fallback.packageName = fallbackPackageName;
        fallback.sourceDir = apkPath;
        fallback.publicSourceDir = apkPath;
        fallback.uid = -1;
        return fallback;
    }

    private static int readLegacyMinApiVersion(ApplicationInfo applicationInfo) {
        if (applicationInfo == null || applicationInfo.metaData == null) {
            return 0;
        }
        Object value = applicationInfo.metaData.get("xposedminversion");
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    @Override
    public List<LoadedModule> getLegacyModules() throws RemoteException {
        return legacyModules;
    }

    @Override
    public List<LoadedModule> getModules() throws RemoteException {
        return modernModules;
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException {
        return "/data/data/" + packageName + "/shared_prefs/";
    }

    @Override
    public ParcelFileDescriptor openManagerApk() throws RemoteException {
        return null;
    }

    @Override
    public IBinder requestManagerService() throws RemoteException {
        return null;
    }

    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    @Override
    public void attachProcessChannel(IProcessChannel target) {
        // Integrated configuration has no manager process that can issue a reload request.
    }

}
