package org.lsposed.npatch.service;

import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.lsposed.npatch.loader.util.FileUtils;
import org.lsposed.npatch.share.Constants;
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

    private final List<Module> modules = new ArrayList<>();

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
                module.file = ModuleLoader.loadModule(cacheApkPath);
                modules.add(module);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error when initializing IntegrApplicationServiceClient", e);
        }
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        return modules;
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return new ArrayList<>();
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