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
import java.util.List;

public class LocalApplicationService extends ILSPApplicationService.Stub {
    private static final String TAG = "NPatch";
    private final List<Module> cachedModule;
    public LocalApplicationService(Context context){
        SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
        cachedModule = new ArrayList<>();
        try {
            JSONArray mArr = new JSONArray(shared.getString("modules", "{}"));
            Log.i(TAG,"use fixed local application service:"+shared.getString("modules", "{}"));
            for (int i = 0; i < mArr.length(); i++) {
                JSONObject mObj = mArr.getJSONObject(i);
                Module m = new Module();
                String path = mObj.getString("path");
                String packageName = mObj.getString("packageName");
                m.apkPath = path;
                m.packageName = packageName;
                if (!new File(m.apkPath).exists()){
                    Log.i("NPatch","Module:" + m.packageName + " path not available, reset.");
                    try {
                        ApplicationInfo info = context.getPackageManager().getApplicationInfo(m.packageName, 0);
                        m.apkPath = info.sourceDir;
                        Log.i("NPatch","Module:" + m.packageName + " path reset to " + m.apkPath);
                    }catch (Exception e){
                        Log.e("NPatch",Log.getStackTraceString(e));
                    }
                }
                m.file = ModuleLoader.loadModule(m.apkPath);
                cachedModule.add(m);
            }
        }catch (Exception e){
            Log.e(TAG,Log.getStackTraceString(e));
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
        return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/").getAbsolutePath();
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
