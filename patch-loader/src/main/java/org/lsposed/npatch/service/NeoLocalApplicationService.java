package org.lsposed.npatch.service;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.lsposed.npatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoLocalApplicationService extends ILSPApplicationService.Stub {
    private static final String TAG = "NPatch";
    private final List<Module> cachedModule;

    public NeoLocalApplicationService(Context context) {
        cachedModule = Collections.synchronizedList(new ArrayList<>());
        loadModulesFromSharedPreferences(context);
    }

    private void loadModulesFromSharedPreferences(Context context) {
        var shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
        try {
            var modulesJsonString = shared.getString("modules", "[]");
            Log.i(TAG, "Loading modules from local SharedPreferences...");

            if (modulesJsonString.equals("{}")) {
                modulesJsonString = "[]";
            }

            var mArr = new JSONArray(modulesJsonString);
            if (mArr.length() > 0) {
                Log.i(TAG, "Found " + mArr.length() + " modules.");
            }

            for (int i = 0; i < mArr.length(); i++) {
                var mObj = mArr.getJSONObject(i);
                var m = new Module();

                m.packageName = mObj.optString("packageName", null);
                var apkPath = mObj.optString("path", null);

                if (m.packageName == null) {
                    Log.w(TAG, "Module at index " + i + " has no package name, skipping.");
                    continue;
                }

                // 如果路徑為 null 或文件不存在，嘗試從 PackageManager 恢復
                if (apkPath == null || !new File(apkPath).exists()) {
                    Log.w(TAG, "Module:" + m.packageName + " path not available, attempting reset.");
                    try {
                        var info = context.getPackageManager().getApplicationInfo(m.packageName, 0);
                        m.apkPath = info.sourceDir;
                        Log.i(TAG, "Module:" + m.packageName + " path reset to " + m.apkPath);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to get ApplicationInfo for module: " + m.packageName, e);
                        continue;
                    }
                } else {
                    m.apkPath = apkPath;
                }

                if (m.apkPath != null) {
                    m.file = ModuleLoader.loadModule(m.apkPath);
                    cachedModule.add(m);
                } else {
                    Log.w(TAG, "Could not load module " + m.packageName + ": final path is null.");
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error loading modules from SharedPreferences.", e);
        }
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        return cachedModule;
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
