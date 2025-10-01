package org.lsposed.lspatch.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lspatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoLocalApplicationService extends ILSPApplicationService.Stub {
    private static final String TAG = "NPatch";
    private final List<Module> cachedModule;

    public NeoLocalApplicationService(Context context){
        var shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
        cachedModule = new ArrayList<>();

        try {
            var modulesJsonString = shared.getString("modules", "[]");
            Log.i(TAG, "Using fixed local application service, config: " + modulesJsonString);

            var mArr = new JSONArray(modulesJsonString);
            for (int i = 0; i < mArr.length(); i++) {
                var mObj = mArr.getJSONObject(i);
                var module = new Module();

                module.apkPath = mObj.getString("path");
                module.packageName = mObj.getString("packageName");

                if (!new File(module.apkPath).exists()){
                    Log.w(TAG, String.format("Module: %s path %s not available. Attempting reset.",
                            module.packageName, module.apkPath));
                    try {
                        var info = context.getPackageManager().getApplicationInfo(module.packageName, 0);
                        module.apkPath = info.sourceDir;
                        Log.i(TAG, String.format("Module: %s path successfully reset to %s.",
                                module.packageName, module.apkPath));
                    } catch (Exception e) {
                        Log.e(TAG, String.format("Failed to reset path for module: %s", module.packageName), e);
                        continue;
                    }
                }

                try {
                    module.file = ModuleLoader.loadModule(module.apkPath);
                    cachedModule.add(module);
                } catch (Exception e) {
                    Log.e(TAG, String.format("Failed to load module file: %s", module.apkPath), e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing NeoLocalApplicationService", e);
        }
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        return Collections.unmodifiableList(cachedModule);
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return new ArrayList<>();
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException {
        return new File(Environment.getDataDirectory(), "data" + File.separator + packageName + File.separator + "shared_prefs" + File.separator).getAbsolutePath();
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
