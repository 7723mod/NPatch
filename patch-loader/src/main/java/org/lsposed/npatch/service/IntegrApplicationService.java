package org.lsposed.npatch.service;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.lsposed.npatch.loader.util.FileUtils;
import org.lsposed.npatch.share.Constants;
import org.lsposed.npatch.util.LocalInjectedModuleService;
import org.lsposed.npatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class IntegrApplicationService extends ILSPApplicationService.Stub {

    private static final String TAG = "NPatch";

    private final List<Module> legacyModules = new ArrayList<>();
    private final List<Module> modernModules = new ArrayList<>();

    public IntegrApplicationService(Context context) {
        try {
            String[] assetsList = context.getAssets().list("npatch/modules");
            if (assetsList == null || assetsList.length == 0) {
                return;
            }

            for (var name : assetsList) {
                if (name == null || name.length() <= 4) continue;

                String packageName = name.substring(0, name.length() - 4);
                String modulePath = context.getCacheDir() + "/npatch/" + packageName + "/";
                String cacheApkPath;

                try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
                    ZipEntry entry = sourceFile.getEntry(Constants.EMBEDDED_MODULES_ASSET_PATH + name);
                    if (entry == null) {
                        Log.w(TAG, "Skipping module (entry not found in APK): " + name);
                        continue;
                    }
                    cacheApkPath = modulePath + entry.getCrc() + ".apk";
                }

                if (!Files.exists(Paths.get(cacheApkPath))) {
                    Log.i(TAG, "Extracting embedded module: " + packageName);
                    FileUtils.deleteFolderIfExists(Paths.get(modulePath));
                    Files.createDirectories(Paths.get(modulePath));
                    try (var is = context.getAssets().open("npatch/modules/" + name)) {
                        Files.copy(is, Paths.get(cacheApkPath));
                    }
                }

                var module = new Module();
                module.apkPath = cacheApkPath;
                module.packageName = packageName;
                module.applicationInfo = readApplicationInfo(context, cacheApkPath, packageName);
                module.file = ModuleLoader.loadModule(cacheApkPath, readLegacyMinApiVersion(module.applicationInfo));
                if (module.file == null) {
                    Log.w(TAG, "Skipping unsupported or unreadable embedded module: " + packageName);
                    continue;
                }
                module.appId = module.applicationInfo == null ? -1 : module.applicationInfo.uid;
                module.service = new LocalInjectedModuleService(context, module.packageName);
                if (module.file != null && module.file.legacy) {
                    legacyModules.add(module);
                } else {
                    modernModules.add(module);
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
            Log.w(TAG, "Failed to read embedded module ApplicationInfo: " + fallbackPackageName, e);
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
    public List<Module> getLegacyModulesList() throws RemoteException {
        return legacyModules;
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return modernModules;
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException {
        return "/data/data/" + packageName + "/shared_prefs/";
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException {
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

}
