package top.nkbe.npatch.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import top.nkbe.npatch.loader.util.XLog;
import top.nkbe.npatch.util.LocalInjectedModuleService;
import top.nkbe.npatch.util.ManagerRemoteServiceBridge;
import top.nkbe.npatch.util.ModuleLoader;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.lsposed.lspd.service.IHotReloadTarget;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class NeoLocalApplicationService extends ILSPApplicationService.Stub {
    private static final String TAG = "NPatch";
    private static final String AUTHORITY = "top.nkbe.npatch.manager.provider.config";
    private static final Uri PROVIDER_URI = Uri.parse("content://" + AUTHORITY + "/config");
    private static final long PROVIDER_TIMEOUT_SECONDS = 3;

    private static final class ProviderResult {
        final List<Module> legacyModules;
        final List<Module> modernModules;
        final JSONArray cache;

        ProviderResult(
                List<Module> legacyModules,
                List<Module> modernModules,
                JSONArray cache
        ) {
            this.legacyModules = legacyModules;
            this.modernModules = modernModules;
            this.cache = cache;
        }
    }

    private final List<Module> legacyModules;
    private final List<Module> modernModules;

    public NeoLocalApplicationService(Context context) {
        legacyModules = Collections.synchronizedList(new ArrayList<>());
        modernModules = Collections.synchronizedList(new ArrayList<>());
        ProviderResult providerResult = loadModulesFromProvider(context);

        if (providerResult != null) {
            legacyModules.addAll(providerResult.legacyModules);
            modernModules.addAll(providerResult.modernModules);
            updateModulesCache(context, providerResult.cache);
        } else {
            Log.w(TAG, "NeoLocal: Provider unavailable, falling back to local cache.");
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
                    loadModuleByPath(context, packageName, path);
                } else if (packageName != null) {
                    loadSingleModule(
                            context,
                            pm,
                            packageName,
                            false,
                            legacyModules,
                            modernModules
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "NeoLocal: Failed to load from cache", e);
        }
    }

    private void loadModuleByPath(Context context, String pkgName, String path) {
        try {
            Module m = new Module();
            m.packageName = pkgName;
            m.apkPath = path;
            m.applicationInfo = readApplicationInfo(context, path, pkgName);
            m.file = ModuleLoader.loadModule(m.apkPath, readLegacyMinApiVersion(m.applicationInfo));
            if (m.file == null) {
                Log.w(TAG, "NeoLocal: Skipping unsupported cached module " + pkgName);
                return;
            }
            m.appId = m.applicationInfo == null ? -1 : m.applicationInfo.uid;
            m.service = new LocalInjectedModuleService(context, m.packageName);
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

    private ProviderResult loadModulesFromProvider(Context context) {
        FutureTask<ProviderResult> query =
                new FutureTask<>(() -> queryModulesFromProvider(context));
        Thread worker = new Thread(query, "NeoLocal-Provider");
        worker.setDaemon(true);
        worker.start();
        try {
            return query.get(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            query.cancel(true);
            Log.w(TAG, "NeoLocal: Manager Provider timed out, using cache");
        } catch (InterruptedException exception) {
            query.cancel(true);
            Thread.currentThread().interrupt();
            Log.w(TAG, "NeoLocal: Manager Provider query interrupted");
        } catch (ExecutionException exception) {
            Log.e(TAG, "NeoLocal: Manager Provider query failed", exception.getCause());
        }
        return null;
    }

    private ProviderResult queryModulesFromProvider(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        String myPackageName = context.getPackageName();
        JSONArray cacheArray = new JSONArray();
        List<Module> providerLegacyModules = new ArrayList<>();
        List<Module> providerModernModules = new ArrayList<>();

        Uri queryUri = PROVIDER_URI.buildUpon()
                .appendQueryParameter("package", myPackageName)
                .build();

        try (Cursor cursor = context.getContentResolver().query(queryUri, null, null, null, null)) {
            if (cursor == null) {
                Log.w(TAG, "NeoLocal: Cannot reach Manager Provider.");
                return null;
            }

            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                int colIndex = cursor.getColumnIndex("packageName");
                if (colIndex != -1) {
                    String packageName = cursor.getString(colIndex);
                    String apkPath = loadSingleModule(
                            context,
                            pm,
                            packageName,
                            true,
                            providerLegacyModules,
                            providerModernModules
                    );
                    if (apkPath != null) {
                        JSONObject moduleObj = new JSONObject();
                        moduleObj.put("path", apkPath);
                        moduleObj.put("packageName", packageName);
                        cacheArray.put(moduleObj);
                    }
                }
            }
            return new ProviderResult(
                    providerLegacyModules,
                    providerModernModules,
                    cacheArray
            );
        }
    }

    private String loadSingleModule(
            Context context,
            PackageManager pm,
            String pkgName,
            boolean managerBacked,
            List<Module> legacyTarget,
            List<Module> modernTarget
    ) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
            Module m = new Module();
            m.packageName = pkgName;
            m.apkPath = appInfo.sourceDir;

            if (m.apkPath != null && new File(m.apkPath).exists()) {
                m.applicationInfo = appInfo;
                m.file = ModuleLoader.loadModule(m.apkPath, readLegacyMinApiVersion(m.applicationInfo));
                if (m.file == null) {
                    Log.w(TAG, "NeoLocal: Skipping unsupported module " + pkgName);
                    return null;
                }
                m.appId = appInfo.uid;
                m.service = managerBacked
                        ? managerBackedService(context, m.packageName)
                        : new LocalInjectedModuleService(context, m.packageName);
                if (m.file != null && m.file.legacy) {
                    legacyTarget.add(m);
                } else {
                    modernTarget.add(m);
                }
                Log.i(TAG, "NeoLocal: Loaded module " + pkgName);
                return m.apkPath;
            }
        } catch (Throwable e) {
            Log.e(TAG, "NeoLocal: Failed to load " + pkgName, e);
        }
        return null;
    }

    private static org.lsposed.lspd.service.ILSPInjectedModuleService managerBackedService(
            Context context,
            String modulePackageName
    ) {
        try {
            return ManagerRemoteServiceBridge.connect(context, modulePackageName);
        } catch (Throwable throwable) {
            Log.w(
                    TAG,
                    "NeoLocal: Manager remote store unavailable for " + modulePackageName
                            + ", using target-local fallback",
                    throwable
            );
            return new LocalInjectedModuleService(context, modulePackageName);
        }
    }

    private void updateModulesCache(Context context, JSONArray modules) {
        try {
            SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
            shared.edit().putString("modules", modules.toString()).apply();
            XLog.i(TAG, "NeoLocal: Updated local modules cache: " + modules);
        } catch (Throwable e) {
            XLog.e(TAG, "NeoLocal: Failed to update local modules cache", e);
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
            Log.w(TAG, "NeoLocal: Failed to read cached module ApplicationInfo: " + fallbackPackageName, e);
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

    @Override
    public void registerHotReloadTarget(IHotReloadTarget target) {
        // Embedded configuration has no manager process that can issue a reload request.
    }
}
