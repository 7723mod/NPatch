package org.lsposed.npatch.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.npatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoLocalApplicationService extends ILSPApplicationService.Stub {
    private static final String TAG = "NPatch";
    private static final String AUTHORITY = "org.lsposed.npatch.manager.provider.config";
    private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY + "/config");

    private final List<Module> legacyModules;
    private final List<Module> modernModules;

    public NeoLocalApplicationService(Context context) {
        legacyModules = Collections.synchronizedList(new ArrayList<>());
        modernModules = Collections.synchronizedList(new ArrayList<>());
        loadModulesFromProvider(context);

        if (legacyModules.isEmpty() && modernModules.isEmpty()) {
            Log.w(TAG, "NeoLocal: Provider returned empty, falling back to local cache.");
            loadModulesFromCache(context);
        }
    }

    private void loadModulesFromCache(Context context) {
        try {
            SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
            String jsonStr = shared.getString("modules", "[]");
            JSONArray jsonArray = new JSONArray(jsonStr);
            PackageManager pm = context.getPackageManager();

            Log.i(TAG, "NeoLocal: Loading from cache: " + jsonStr);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String packageName = obj.optString("packageName");
                String path = obj.optString("path");

                if (path != null && !path.isEmpty() && new File(path).exists()) {
                    loadModuleByPath(packageName, path);
                } else if (packageName != null) {
                    loadSingleModule(pm, packageName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "NeoLocal: Failed to load from cache", e);
        }
    }

    private void loadModuleByPath(String pkgName, String path) {
        try {
            Module m = new Module();
            m.packageName = pkgName;
            m.apkPath = path;
            m.file = ModuleLoader.loadModule(m.apkPath);
            if (m.file != null && m.file.legacy) {
                legacyModules.add(m);
            } else {
                modernModules.add(m);
            }
            Log.i(TAG, "Loaded cached module " + pkgName);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load cached module " + pkgName, e);
        }
    }

    private void loadModulesFromProvider(Context context) {
        PackageManager pm = context.getPackageManager();
        String myPackageName = context.getPackageName();

        Uri queryUri = PROVIDER_URI.buildUpon()
                .appendQueryParameter("package", myPackageName)
                .build();

        try (Cursor cursor = context.getContentResolver().query(queryUri, null, null, null, null)) {
            if (cursor == null) {
                Log.w(TAG, "NeoLocal: Cannot reach Manager Provider.");
                return;
            }

            while (cursor.moveToNext()) {
                int colIndex = cursor.getColumnIndex("packageName");
                if (colIndex != -1) {
                    loadSingleModule(pm, cursor.getString(colIndex));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "NeoLocal: Provider query failed", e);
        }
    }

    private void loadSingleModule(PackageManager pm, String pkgName) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
            Module m = new Module();
            m.packageName = pkgName;
            m.apkPath = appInfo.sourceDir;

            if (m.apkPath != null && new File(m.apkPath).exists()) {
                m.file = ModuleLoader.loadModule(m.apkPath);
                if (m.file != null && m.file.legacy) {
                    legacyModules.add(m);
                } else {
                    modernModules.add(m);
                }
                Log.i(TAG, "NeoLocal: Loaded module " + pkgName);
            }
        } catch (Throwable e) {
            Log.e(TAG, "NeoLocal: Failed to load " + pkgName, e);
        }
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
    public String getPrefsPath(String packageName) throws RemoteException { return "/data/data/" + packageName + "/shared_prefs/"; }
    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException { return null; }
    @Override
    public IBinder asBinder() {
        return this;
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }
}
