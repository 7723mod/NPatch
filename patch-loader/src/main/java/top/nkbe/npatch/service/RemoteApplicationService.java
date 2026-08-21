package top.nkbe.npatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.LoadedModule;

import top.nkbe.npatch.loader.util.XLog;
import top.nkbe.npatch.share.Constants;
import top.nkbe.npatch.util.ModuleLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class RemoteApplicationService implements IFrameworkService {

    private static final String TAG = "NPatch";
    private static final String MODULE_SERVICE = "top.nkbe.npatch.manager.ModuleService";
    private static final int CONNECTION_TIMEOUT_SEC = 2;
    private static final long MAX_BACKGROUND_WAIT_MS = 5 * 60 * 1000L;
    private static final int REGISTER_CLIENT_PACKAGE = 0x4E5041;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService BIND_EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "NPatch-ManagerBind");
                thread.setDaemon(true);
                return thread;
            });

    private final Context context;
    private final AtomicBoolean managerAvailable = new AtomicBoolean(false);
    private final Map<String, FallbackModuleServiceWrapper> dynamicModuleServices = new ConcurrentHashMap<>();
    private volatile List<LoadedModule> cachedLegacyModuleScope = Collections.emptyList();
    private volatile List<LoadedModule> cachedModuleScope = Collections.emptyList();
    private volatile IFrameworkService service;
    private volatile ServiceConnection connection;
    private Runnable giveUpRunnable;

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) {
        Context appContext = context.getApplicationContext();
        this.context = appContext == null ? context : appContext;

        CountDownLatch bindLatch = new CountDownLatch(1);
        AtomicBoolean connectedOnTime = new AtomicBoolean(false);

        Intent intent = new Intent()
                .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE))
                .putExtra("packageName", this.context.getPackageName());

        ServiceConnection candidate = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    registerClientPackage(binder, RemoteApplicationService.this.context.getPackageName());
                    IFrameworkService connected = Stub.asInterface(binder);
                    if (connected == null || !binder.isBinderAlive()) {
                        throw new RemoteException("Manager returned a dead binder");
                    }
                    service = connected;
                    managerAvailable.set(true);
                    connection = this;
                    Log.i(TAG, "Manager binder connected and registered successfully");

                    // Restore Process Channel (Hot reload)
                    try {
                        connected.attachProcessChannel(new NPatchProcessChannel());
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to restore hot reload process channel on reconnect", t);
                    }

                    // Fetch latest modules from Manager and dynamically upgrade module wrappers
                    BIND_EXECUTOR.execute(() -> onManagerReconnected(connected));
                } catch (Throwable error) {
                    Log.w(TAG, "Manager binder connection setup failed", error);
                    managerAvailable.set(false);
                } finally {
                    bindLatch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.w(TAG, "Manager service disconnected");
                managerAvailable.set(false);
                recordFallbackEvent("manager_disconnected");
                dynamicModuleServices.values().forEach(wrapper -> wrapper.setRemoteService(null));
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Log.w(TAG, "Manager binding died");
                managerAvailable.set(false);
                recordFallbackEvent("binding_died");
                dynamicModuleServices.values().forEach(wrapper -> wrapper.setRemoteService(null));
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Log.w(TAG, "Manager returned a null binding");
                managerAvailable.set(false);
                recordFallbackEvent("null_binding");
                bindLatch.countDown();
            }
        };

        // 1. Asynchronously initiate bind
        boolean bindInitiated = false;
        try {
            bindInitiated = bindServiceCompat(intent, candidate);
        } catch (Throwable t) {
            Log.w(TAG, "bindServiceCompat failed immediately", t);
        }

        // 2. Concurrently load modules from local cache
        List<LoadedModule> localLegacy = new ArrayList<>();
        List<LoadedModule> localModern = new ArrayList<>();
        loadModulesFromCache(this.context, localLegacy, localModern);

        // 3. Wait up to CONNECTION_TIMEOUT_SEC for manager to respond
        if (bindInitiated) {
            try {
                connectedOnTime.set(bindLatch.await(CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        IFrameworkService active = service;
        if (connectedOnTime.get() && active != null && active.asBinder().isBinderAlive()) {
            Log.i(TAG, "Manager connected within startup deadline");
            try {
                cacheModuleScope(true, active.getLegacyModules());
                cacheModuleScope(false, active.getModules());
                updateModulesCache(this.context);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to read initial modules from manager, fallback to local cache", t);
                applyLocalCachedModules(localLegacy, localModern);
            }
        } else {
            Log.w(TAG, "Manager not ready in time; starting with local cached modules");
            recordFallbackEvent("bind_timeout");
            applyLocalCachedModules(localLegacy, localModern);

            // Schedule give-up timer for background connection
            if (bindInitiated) {
                this.connection = candidate;
                scheduleGiveUp(candidate);
            }
        }
    }

    private void scheduleGiveUp(ServiceConnection candidate) {
        giveUpRunnable = () -> {
            if (!managerAvailable.get() && connection == candidate) {
                Log.i(TAG, "Max background wait reached (5m), unbinding manager connection");
                safeUnbind(candidate);
                connection = null;
            }
        };
        MAIN_HANDLER.postDelayed(giveUpRunnable, MAX_BACKGROUND_WAIT_MS);
    }

    private void applyLocalCachedModules(List<LoadedModule> localLegacy, List<LoadedModule> localModern) {
        for (LoadedModule m : localLegacy) {
            FallbackModuleServiceWrapper wrapper = dynamicModuleServices.computeIfAbsent(
                    m.packageName,
                    pkg -> new FallbackModuleServiceWrapper(context, pkg, null)
            );
            m.service = wrapper;
        }
        for (LoadedModule m : localModern) {
            FallbackModuleServiceWrapper wrapper = dynamicModuleServices.computeIfAbsent(
                    m.packageName,
                    pkg -> new FallbackModuleServiceWrapper(context, pkg, null)
            );
            m.service = wrapper;
        }
        cachedLegacyModuleScope = new ArrayList<>(localLegacy);
        cachedModuleScope = new ArrayList<>(localModern);
    }

    private void onManagerReconnected(IFrameworkService connected) {
        try {
            List<LoadedModule> remoteLegacy = connected.getLegacyModules();
            List<LoadedModule> remoteModern = connected.getModules();

            if (remoteLegacy != null) {
                for (LoadedModule rm : remoteLegacy) {
                    if (rm != null && rm.packageName != null) {
                        FallbackModuleServiceWrapper wrapper = dynamicModuleServices.computeIfAbsent(
                                rm.packageName,
                                pkg -> new FallbackModuleServiceWrapper(context, pkg, rm.service)
                        );
                        wrapper.setRemoteService(rm.service);
                    }
                }
            }

            if (remoteModern != null) {
                for (LoadedModule rm : remoteModern) {
                    if (rm != null && rm.packageName != null) {
                        FallbackModuleServiceWrapper wrapper = dynamicModuleServices.computeIfAbsent(
                                rm.packageName,
                                pkg -> new FallbackModuleServiceWrapper(context, pkg, rm.service)
                        );
                        wrapper.setRemoteService(rm.service);
                    }
                }
            }

            cacheModuleScope(true, remoteLegacy);
            cacheModuleScope(false, remoteModern);
            updateModulesCache(context);
            Log.i(TAG, "Successfully synced remote modules and services after reconnection");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to sync remote modules on reconnect", t);
        }
    }

    private void recordFallbackEvent(String reason) {
        try {
            SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
            shared.edit()
                    .putLong("last_fallback_ts", System.currentTimeMillis())
                    .putString("last_fallback_reason", reason)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    private void updateModulesCache(Context context) {
        try {
            JSONArray moduleArr = new JSONArray();
            Map<String, String> cachedModules = new LinkedHashMap<>();

            for (LoadedModule m : cachedLegacyModuleScope) {
                if (m != null && m.packageName != null && m.apkPath != null) {
                    cachedModules.put(m.packageName, m.apkPath);
                }
            }
            for (LoadedModule m : cachedModuleScope) {
                if (m != null && m.packageName != null && m.apkPath != null) {
                    cachedModules.put(m.packageName, m.apkPath);
                }
            }

            for (Map.Entry<String, String> entry : cachedModules.entrySet()) {
                JSONObject moduleObj = new JSONObject();
                moduleObj.put("path", entry.getValue());
                moduleObj.put("packageName", entry.getKey());
                moduleArr.put(moduleObj);
            }
            SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
            shared.edit().putString("modules", moduleArr.toString()).apply();
            XLog.i(TAG, "Updated local module scope cache: " + moduleArr);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to update local module scope cache", e);
        }
    }

    private void loadModulesFromCache(
            Context context,
            List<LoadedModule> legacyTarget,
            List<LoadedModule> modernTarget
    ) {
        try {
            SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
            String jsonStr = shared.getString("modules", "[]");
            JSONArray jsonArray = new JSONArray(jsonStr);
            PackageManager pm = context.getPackageManager();

            Log.i(TAG, "Loading modules from local cache: " + jsonStr);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                String packageName = obj.optString("packageName");
                String path = obj.optString("path");

                if (path != null && !path.isEmpty() && new File(path).exists()) {
                    loadModuleByPath(context, packageName, path, legacyTarget, modernTarget);
                } else if (packageName != null && pm != null) {
                    loadSingleModuleByPm(context, pm, packageName, legacyTarget, modernTarget);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load modules from cache", e);
        }
    }

    private void loadModuleByPath(
            Context context,
            String pkgName,
            String path,
            List<LoadedModule> legacyTarget,
            List<LoadedModule> modernTarget
    ) {
        try {
            LoadedModule m = new LoadedModule();
            m.packageName = pkgName;
            m.apkPath = path;
            m.applicationInfo = readApplicationInfo(context, path, pkgName);
            var parsedModule = ModuleLoader.loadModule(
                    m.apkPath,
                    readLegacyMinApiVersion(m.applicationInfo));
            m.code = parsedModule == null ? null : parsedModule.code;
            if (m.code == null) {
                Log.w(TAG, "Skipping unsupported cached module " + pkgName);
                return;
            }
            m.appId = m.applicationInfo == null ? -1 : m.applicationInfo.uid;
            if (m.code.legacy) {
                legacyTarget.add(m);
            } else {
                modernTarget.add(m);
            }
            Log.i(TAG, "Loaded cached module " + pkgName);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to load cached module " + pkgName, e);
        }
    }

    private void loadSingleModuleByPm(
            Context context,
            PackageManager pm,
            String pkgName,
            List<LoadedModule> legacyTarget,
            List<LoadedModule> modernTarget
    ) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
            if (appInfo.sourceDir != null && new File(appInfo.sourceDir).exists()) {
                loadModuleByPath(context, pkgName, appInfo.sourceDir, legacyTarget, modernTarget);
            }
        } catch (Throwable ignored) {
        }
    }

    private static ApplicationInfo readApplicationInfo(Context context, String apkPath, String fallbackPackageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            if (packageManager != null) {
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
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to read cached module ApplicationInfo: " + fallbackPackageName, e);
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

    @SuppressLint("DiscouragedPrivateApi")
    private boolean bindServiceCompat(Intent intent, ServiceConnection candidate)
            throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.bindService(
                    intent,
                    Context.BIND_AUTO_CREATE,
                    BIND_EXECUTOR,
                    candidate
            );
        }

        Class<?> contextImplClass = context.getClass();
        Method getUserMethod = contextImplClass.getMethod("getUser");
        UserHandle userHandle = (UserHandle) getUserMethod.invoke(context);
        Method bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                "bindServiceAsUser",
                Intent.class,
                ServiceConnection.class,
                int.class,
                Handler.class,
                UserHandle.class
        );
        Object result = bindServiceAsUserMethod.invoke(
                context,
                intent,
                candidate,
                Context.BIND_AUTO_CREATE,
                new Handler(Looper.getMainLooper()),
                userHandle
        );
        return !(result instanceof Boolean) || (Boolean) result;
    }

    private void safeUnbind(ServiceConnection candidate) {
        try {
            context.unbindService(candidate);
        } catch (IllegalArgumentException ignored) {
            // The framework may dispatch a late callback after an already completed unbind.
        }
    }

    private static void registerClientPackage(IBinder binder, String packageName)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("org.matrix.vector.ipc.IFrameworkService");
            data.writeString(packageName);
            if (!binder.transact(REGISTER_CLIENT_PACKAGE, data, reply, 0)) {
                throw new RemoteException("Manager does not support caller registration");
            }
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public List<LoadedModule> getLegacyModules() throws RemoteException {
        IFrameworkService current = service;
        if (current == null || !managerAvailable.get()) {
            return getCachedModuleScope(true);
        }
        try {
            return cacheModuleScope(true, current.getLegacyModules());
        } catch (RemoteException error) {
            onManagerFailure("get legacy modules", error);
            return getCachedModuleScope(true);
        }
    }

    @Override
    public List<LoadedModule> getModules() throws RemoteException {
        IFrameworkService current = service;
        if (current == null || !managerAvailable.get()) {
            return getCachedModuleScope(false);
        }
        try {
            return cacheModuleScope(false, current.getModules());
        } catch (RemoteException error) {
            onManagerFailure("get modules", error);
            return getCachedModuleScope(false);
        }
    }

    private List<LoadedModule> cacheModuleScope(boolean legacy, List<LoadedModule> modules) {
        List<LoadedModule> protectedModules = protectModules(modules);
        List<LoadedModule> snapshot = protectedModules == null || protectedModules.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(protectedModules);
        if (legacy) {
            cachedLegacyModuleScope = snapshot;
        } else {
            cachedModuleScope = snapshot;
        }
        return new ArrayList<>(snapshot);
    }

    private List<LoadedModule> getCachedModuleScope(boolean legacy) {
        return new ArrayList<>(legacy ? cachedLegacyModuleScope : cachedModuleScope);
    }

    private List<LoadedModule> protectModules(List<LoadedModule> modules) {
        if (modules == null || modules.isEmpty()) {
            return modules;
        }
        for (LoadedModule loadedModule : modules) {
            if (loadedModule == null || loadedModule.packageName == null) {
                continue;
            }
            FallbackModuleServiceWrapper wrapper = dynamicModuleServices.computeIfAbsent(
                    loadedModule.packageName,
                    pkg -> new FallbackModuleServiceWrapper(context, pkg, loadedModule.service)
            );
            if (loadedModule.service != null) {
                wrapper.setRemoteService(loadedModule.service);
            }
            loadedModule.service = wrapper;
        }
        return modules;
    }

    @Override
    public String getPrefsPath(String packageName) {
        IFrameworkService current = service;
        if (current == null || !managerAvailable.get()) {
            return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/")
                    .getAbsolutePath();
        }
        try {
            return current.getPrefsPath(packageName);
        } catch (RemoteException e) {
            onManagerFailure("get preferences path", e);
            return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/")
                    .getAbsolutePath();
        }
    }

    @Override
    public IBinder asBinder() {
        return service == null ? null : service.asBinder();
    }

    @Override
    public ParcelFileDescriptor openManagerApk() {
        IFrameworkService current = service;
        if (current == null || !managerAvailable.get()) {
            return null;
        }
        try {
            return current.openManagerApk();
        } catch (RemoteException e) {
            onManagerFailure("open manager apk", e);
            return null;
        }
    }

    @Override
    public IBinder requestManagerService() {
        IFrameworkService current = service;
        if (current == null || !managerAvailable.get()) {
            return null;
        }
        try {
            return current.requestManagerService();
        } catch (RemoteException e) {
            onManagerFailure("request injected manager binder", e);
            return null;
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    @Override
    public void attachProcessChannel(IProcessChannel target) throws RemoteException {
        IFrameworkService current = service;
        if (current != null && managerAvailable.get()) {
            try {
                current.attachProcessChannel(new NPatchProcessChannel());
            } catch (RemoteException error) {
                onManagerFailure("register hot reload target", error);
            }
        }
    }

    private void onManagerFailure(String operation, RemoteException error) {
        managerAvailable.set(false);
        recordFallbackEvent("manager_call_failed_" + operation);
        Log.w(TAG, "Manager unavailable while attempting to " + operation
                + "; using local fallback", error);
    }
}

