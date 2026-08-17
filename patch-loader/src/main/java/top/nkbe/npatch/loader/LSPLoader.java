package top.nkbe.npatch.loader;

import android.app.ActivityThread;
import android.app.Application;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dalvik.system.PathClassLoader;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedInterface.ExceptionMode;
import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.IRemotePreferenceCallback;
import org.matrix.vector.Startup;
import org.matrix.vector.impl.VectorContext;
import org.matrix.vector.impl.VectorLifecycleManager;
import org.matrix.vector.impl.core.VectorServiceClient;
import top.nkbe.npatch.share.LSPConfig;
import org.matrix.vector.nativebridge.NativeAPI;

public class LSPLoader {
    private static final String TAG = "NPatch-Loader";
    private static final Set<String> enhancedLoadedModules = new LinkedHashSet<>();
    private static final Map<String, ApplicationInfo> moduleRuntimeAppInfos = new ConcurrentHashMap<>();
    private static volatile boolean moduleSelfPathHooked;

    public static Map<String, String> getActiveModuleApkPaths() {
        Map<String, String> result = new java.util.HashMap<>();
        for (Map.Entry<String, ApplicationInfo> e : moduleRuntimeAppInfos.entrySet()) {
            ApplicationInfo info = e.getValue();
            if (info != null && info.sourceDir != null) {
                result.put(e.getKey(), info.sourceDir);
            }
        }
        return result;
    }

    public static void initModules(LoadedApk loadedApk) {
        String ver = LSPConfig.instance.VERSION_NAME;
        XposedBridge.FRAMEWORK_NAME = "NPatch";
        XposedBridge.FRAMEWORK_VERSION = ver.startsWith("v") ? ver : "v" + ver;
        XposedBridge.FRAMEWORK_VERSION_NAME = XposedBridge.FRAMEWORK_VERSION;
        XposedBridge.FRAMEWORK_VERSION_CODE = LSPConfig.instance.VERSION_CODE;
        XposedBridge.XPOSED_BRIDGE_VERSION = 93;

        installNativeModuleServiceProxy();
        registerModuleRuntimeAppInfos();
        installModuleSelfPathCompatibility();
        Startup.trackLoadedApk(loadedApk);
        XposedInit.loadModules(ActivityThread.currentActivityThread());
        ApplicationInfo moduleCompatibleAppInfo =
                SigBypass.createModuleCompatibleApplicationInfo(loadedApk.getApplicationInfo());
        dispatchModernLifecycle(loadedApk, moduleCompatibleAppInfo);

        XposedInit.loadedPackagesInProcess.add(loadedApk.getPackageName());
        String resDir = null;
        try {
            resDir = (String) XposedHelpers.getObjectField(loadedApk, "mResDir");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to get mResDir from LoadedApk", e);
        }
        setPackageNameForResDir(loadedApk.getPackageName(), resDir);
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                XposedBridge.sLoadedPackageCallbacks);
        lpparam.packageName = loadedApk.getPackageName();
        lpparam.processName = ActivityThread.currentProcessName();
        lpparam.classLoader = loadedApk.getClassLoader();
        lpparam.appInfo = moduleCompatibleAppInfo != null
                ? moduleCompatibleAppInfo
                : loadedApk.getApplicationInfo();
        lpparam.isFirstApplication = true;
        XC_LoadPackage.callAll(lpparam);
    }

    private static void registerModuleRuntimeAppInfos() {
        try {
            Field serviceField = VectorServiceClient.class.getDeclaredField("service");
            serviceField.setAccessible(true);
            Object current = serviceField.get(VectorServiceClient.INSTANCE);
            if (!(current instanceof IFrameworkService service)) {
                Log.w(TAG, "VectorServiceClient service is not ready for LoadedModule path compatibility");
                return;
            }

            registerModuleRuntimeAppInfos(service.getLegacyModules());
            registerModuleRuntimeAppInfos(service.getModules());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register LoadedModule runtime ApplicationInfo", e);
        }
    }

    private static void registerModuleRuntimeAppInfos(List<LoadedModule> modules) {
        if (modules == null || modules.isEmpty()) {
            return;
        }
        for (LoadedModule LoadedModule : modules) {
            ApplicationInfo runtimeAppInfo = buildRuntimeApplicationInfo(LoadedModule);
            if (runtimeAppInfo == null || runtimeAppInfo.packageName == null) {
                continue;
            }
            moduleRuntimeAppInfos.put(runtimeAppInfo.packageName, runtimeAppInfo);
        }
    }

    private static void installModuleSelfPathCompatibility() {
        if (moduleSelfPathHooked) {
            return;
        }
        try {
            XC_MethodHook appInfoHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String packageName)) {
                        return;
                    }
                    ApplicationInfo runtimeAppInfo = findRuntimeAppInfo(packageName);
                    if (runtimeAppInfo == null) {
                        return;
                    }
                    param.setResult(copyApplicationInfo(runtimeAppInfo));
                }
            };
            XC_MethodHook packageInfoHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof String packageName)) {
                        return;
                    }
                    ApplicationInfo runtimeAppInfo = findRuntimeAppInfo(packageName);
                    if (runtimeAppInfo == null) {
                        return;
                    }
                    PackageInfo packageInfo = (PackageInfo) param.getResult();
                    if (packageInfo == null) {
                        return;
                    }
                    packageInfo.applicationInfo = copyApplicationInfo(runtimeAppInfo);
                }
            };

            Class<?> appPmClass = Class.forName("android.app.ApplicationPackageManager");
            XposedBridge.hookAllMethods(appPmClass, "getApplicationInfo", appInfoHook);
            XposedBridge.hookAllMethods(appPmClass, "getApplicationInfoAsUser", appInfoHook);
            XposedBridge.hookAllMethods(appPmClass, "getPackageInfo", packageInfoHook);
            moduleSelfPathHooked = true;
            Log.i(TAG, "Installed LoadedModule self path compatibility hook");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to install LoadedModule self path compatibility hook", e);
        }
    }

    private static ApplicationInfo findRuntimeAppInfo(String packageName) {
        if (!SigBypass.isModuleCallerForCompat()) {
            return null;
        }
        return moduleRuntimeAppInfos.get(packageName);
    }

    private static void installNativeModuleServiceProxy() {
        try {
            Field serviceField = VectorServiceClient.class.getDeclaredField("service");
            serviceField.setAccessible(true);

            Object current = serviceField.get(VectorServiceClient.INSTANCE);
            if (!(current instanceof IFrameworkService)) {
                Log.w(TAG, "VectorServiceClient service is not ready for native LoadedModule proxy");
                return;
            }
            if (current instanceof NativeModuleFilteringService) {
                return;
            }

            serviceField.set(VectorServiceClient.INSTANCE,
                    new NativeModuleFilteringService((IFrameworkService) current));
            Log.i(TAG, "Installed native LoadedModule service proxy");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to install native LoadedModule service proxy", e);
        }
    }

    private static final class NativeModuleFilteringService extends IFrameworkService.Stub {
        private final IFrameworkService base;

        private NativeModuleFilteringService(IFrameworkService base) {
            this.base = base;
        }

        @Override
        public boolean isLogMuted() throws RemoteException {
            return base.isLogMuted();
        }

        @Override
        public List<LoadedModule> getLegacyModules() throws RemoteException {
            return base.getLegacyModules();
        }

        @Override
        public List<LoadedModule> getModules() throws RemoteException {
            List<LoadedModule> modules = base.getModules();
            if (modules == null || modules.isEmpty()) {
                return modules;
            }

            List<LoadedModule> filtered = new ArrayList<>(modules.size());
            String processName = ActivityThread.currentProcessName();
            for (LoadedModule LoadedModule : modules) {
                if (!shouldHandleNative(LoadedModule)) {
                    filtered.add(LoadedModule);
                    continue;
                }

                String key = LoadedModule.packageName + "@" + LoadedModule.apkPath;
                synchronized (enhancedLoadedModules) {
                    if (enhancedLoadedModules.contains(key)) {
                        Log.i(TAG, "Filtering already enhanced native LoadedModule: " + LoadedModule.packageName);
                        continue;
                    }
                }

                Log.i(TAG, "Enhanced loading native LoadedModule before core: " + LoadedModule.packageName);
                if (performEnhancedLoad(LoadedModule, false, processName)) {
                    synchronized (enhancedLoadedModules) {
                        enhancedLoadedModules.add(key);
                    }
                } else {
                    filtered.add(LoadedModule);
                }
            }
            return filtered;
        }

        @Override
        public String getPrefsPath(String packageName) throws RemoteException {
            return base.getPrefsPath(packageName);
        }

        @Override
        public ParcelFileDescriptor openManagerApk() throws RemoteException {
            return base.openManagerApk();
        }

        @Override
        public IBinder requestManagerService() throws RemoteException {
            return base.requestManagerService();
        }

        @Override
        public void attachProcessChannel(IProcessChannel target) throws RemoteException {
            base.attachProcessChannel(target);
        }

        @Override
        public IBinder asBinder() {
            return base.asBinder();
        }
    }

    private static boolean shouldHandleNative(LoadedModule LoadedModule) {
        // Only modules that explicitly declare native entrypoints should go
        // through the eager native path. Plenty of modern modules bundle JNI
        // or third-party .so files but still expect plain Java onModuleLoaded().
        return LoadedModule != null
                && LoadedModule.code != null
                && LoadedModule.code.moduleLibraryNames != null
                && !LoadedModule.code.moduleLibraryNames.isEmpty();
    }

    private static ClassLoader createEnhancedModuleClassLoader(
            LoadedModule LoadedModule,
            String librarySearchPath,
            ClassLoader parent
    ) {
        if (LoadedModule.code.targetApiVersion < 102) {
            return new PathClassLoader(LoadedModule.apkPath, librarySearchPath, parent);
        }
        return new PathClassLoader(LoadedModule.apkPath, librarySearchPath, parent) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.startsWith("de.robv.android.xposed.")) {
                    throw new ClassNotFoundException(
                            name + " is unavailable to modules targeting Xposed API 102 or higher"
                    );
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    private static boolean performEnhancedLoad(LoadedModule LoadedModule, boolean isSystemServer, String processName) {
        try {
            ApplicationInfo moduleAppInfo = buildRuntimeApplicationInfo(LoadedModule);
            File nativeDir = resolvePreparedNativeDir(moduleAppInfo);
            String librarySearchPath = buildLibrarySearchPath(LoadedModule, nativeDir);

            ClassLoader initLoader = XposedModule.class.getClassLoader();
            ClassLoader moduleClassLoader =
                    createEnhancedModuleClassLoader(LoadedModule, librarySearchPath, initLoader);

            VectorContext vectorContext = new VectorContext(
                    LoadedModule.packageName,
                    moduleAppInfo,
                    LoadedModule.service != null ? LoadedModule.service : EMPTY_INJECTED_MODULE_SERVICE,
                    LoadedModule.code.exceptionPassthrough
                            ? ExceptionMode.PASSTHROUGH
                            : ExceptionMode.PROTECTIVE
            );

            for (String libName : discoverNativeLibraries(LoadedModule)) {
                NativeAPI.recordNativeEntrypoint(libName);
            }

            if (LoadedModule.code != null && LoadedModule.code.moduleClassNames != null) {
                for (String className : LoadedModule.code.moduleClassNames) {
                    Class<?> moduleClass = moduleClassLoader.loadClass(className);
                    if (XposedModule.class.isAssignableFrom(moduleClass)) {
                        Constructor<?> ctor = moduleClass.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        XposedModule instance = (XposedModule) ctor.newInstance();

                        instance.attachFramework(
                                vectorContext,
                                () -> VectorLifecycleManager.INSTANCE.detach(instance)
                        );

                        VectorLifecycleManager.INSTANCE.getActiveModules().add(instance);

                        instance.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
                            @Override public boolean isSystemServer() { return isSystemServer; }
                            @Override public String getProcessName() { return processName; }
                        });
                    }
                }
            }

            Log.d(TAG, "Enhanced load successful for " + LoadedModule.packageName);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Enhanced load failed for " + LoadedModule.packageName, e);
            return false;
        }
    }

    private static ApplicationInfo buildRuntimeApplicationInfo(LoadedModule LoadedModule) {
        if (LoadedModule == null || LoadedModule.packageName == null || LoadedModule.apkPath == null) {
            return null;
        }
        ApplicationInfo appInfo = copyApplicationInfo(LoadedModule.applicationInfo);
        if (appInfo == null) {
            appInfo = new ApplicationInfo();
            appInfo.packageName = LoadedModule.packageName;
            appInfo.uid = LoadedModule.appId;
        }
        appInfo.sourceDir = LoadedModule.apkPath;
        appInfo.publicSourceDir = LoadedModule.apkPath;
        appInfo.flags |= ApplicationInfo.FLAG_HAS_CODE;

        File nativeDir = prepareNativeLibraryDir(LoadedModule);
        if (nativeDir != null) {
            appInfo.nativeLibraryDir = nativeDir.getAbsolutePath();
            appInfo.flags |= (1 << 26);
        }
        return appInfo;
    }

    private static ApplicationInfo copyApplicationInfo(ApplicationInfo source) {
        if (source == null) {
            return null;
        }
        try {
            return new ApplicationInfo(source);
        } catch (Throwable ignored) {
            ApplicationInfo copy = new ApplicationInfo();
            copy.packageName = source.packageName;
            copy.sourceDir = source.sourceDir;
            copy.publicSourceDir = source.publicSourceDir;
            copy.nativeLibraryDir = source.nativeLibraryDir;
            copy.dataDir = source.dataDir;
            copy.uid = source.uid;
            copy.flags = source.flags;
            copy.metaData = source.metaData;
            return copy;
        }
    }

    private static File resolvePreparedNativeDir(ApplicationInfo appInfo) {
        if (appInfo == null || appInfo.nativeLibraryDir == null || appInfo.nativeLibraryDir.isEmpty()) {
            return null;
        }
        return new File(appInfo.nativeLibraryDir);
    }

    private static File prepareNativeLibraryDir(LoadedModule LoadedModule) {
        return ModuleNativeCache.prepare(currentApplication(), LoadedModule);
    }

    private static String buildLibrarySearchPath(LoadedModule LoadedModule, File nativeDir) {
        StringBuilder sb = new StringBuilder();
        if (nativeDir != null) {
            sb.append(nativeDir.getAbsolutePath()).append(File.pathSeparator);
        }
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            sb.append(LoadedModule.apkPath).append("!/lib/").append(abi).append(File.pathSeparator);
        }
        return sb.toString();
    }

    private static List<String> buildNativeInitCandidates(LoadedModule LoadedModule, File nativeDir, String libName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (nativeDir != null) {
            candidates.add(new File(nativeDir, libName).getAbsolutePath());
        }
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            candidates.add(LoadedModule.apkPath + "!/lib/" + abi + "/" + libName);
            String normalizedAbi = abi.toLowerCase(Locale.ROOT);
            if (!normalizedAbi.equals(abi)) {
                candidates.add(LoadedModule.apkPath + "!/lib/" + normalizedAbi + "/" + libName);
            }
        }
        return new ArrayList<>(candidates);
    }

    private static List<String> discoverNativeLibraries(LoadedModule LoadedModule) {
        LinkedHashSet<String> libraries = new LinkedHashSet<>();
        if (LoadedModule.code != null && LoadedModule.code.moduleLibraryNames != null) {
            for (String libName : LoadedModule.code.moduleLibraryNames) {
                if (libName == null || libName.isEmpty()) {
                    continue;
                }
                libraries.add(libName);
                if (!libName.startsWith("lib") || !libName.endsWith(".so")) {
                    libraries.add(System.mapLibraryName(libName));
                }
            }
        }
        return new ArrayList<>(libraries);
    }

    private static Application currentApplication() {
        try {
            return (Application) XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication");
        } catch (Throwable ignored) { return null; }
    }

    private static final IModuleService EMPTY_INJECTED_MODULE_SERVICE =
            new IModuleService.Stub() {
                @Override
                public long getFrameworkProperties() {
                    return 0L;
                }

                @Override
                public Bundle requestRemotePreferences(
                        String group,
                        IRemotePreferenceCallback callback
                ) {
                    return Bundle.EMPTY;
                }

                @Override
                public ParcelFileDescriptor openRemoteFile(String path) {
                    return null;
                }

                @Override
                public String[] getRemoteFileNames() {
                    return new String[0];
                }
            };

    private static void dispatchModernLifecycle(LoadedApk loadedApk, ApplicationInfo moduleCompatibleAppInfo) {
        try {
            String packageName = loadedApk.getPackageName();
            ApplicationInfo appInfo = moduleCompatibleAppInfo != null
                    ? moduleCompatibleAppInfo
                    : loadedApk.getApplicationInfo();
            ClassLoader classLoader = loadedApk.getClassLoader();
            ClassLoader defaultClassLoader = null;
            try {
                defaultClassLoader = (ClassLoader) XposedHelpers.getObjectField(loadedApk, "mDefaultClassLoader");
            } catch (Throwable ignored) {
            }
            if (defaultClassLoader == null) {
                defaultClassLoader = classLoader;
            }
            Object appComponentFactory = createAppComponentFactory(appInfo, classLoader);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VectorLifecycleManager.INSTANCE.dispatchPackageLoaded(
                        packageName,
                        appInfo,
                        true,
                        defaultClassLoader);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                VectorLifecycleManager.INSTANCE.dispatchPackageReady(
                        packageName,
                        appInfo,
                        true,
                        defaultClassLoader,
                        classLoader,
                        appComponentFactory);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to dispatch modern Xposed lifecycle", e);
        }
    }

    private static Object createAppComponentFactory(ApplicationInfo appInfo, ClassLoader classLoader) {
        if (appInfo == null || appInfo.appComponentFactory == null || appInfo.appComponentFactory.isEmpty()) {
            return null;
        }
        try {
            Class<?> factoryClass = classLoader.loadClass(appInfo.appComponentFactory);
            return factoryClass.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            Log.w(TAG, "Failed to create AppComponentFactory: " + appInfo.appComponentFactory, e);
            return null;
        }
    }

    private static void setPackageNameForResDir(String packageName, String resDir) {
        try {
            // Use reflection to avoid direct type reference to android.content.res.XResources
            // which fails class resolution on Android 16+ due to strict boot classloader
            // namespace delegation for the android.content.res.* package.
            ClassLoader cl = LSPLoader.class.getClassLoader();
            Class<?> xResourcesClass = cl.loadClass("android.content.res.XResources");
            Method setMethod = xResourcesClass.getMethod("setPackageNameForResDir", String.class, String.class);
            setMethod.invoke(null, packageName, resDir);
        } catch (Throwable e) {
            Log.w(TAG, "XResources.setPackageNameForResDir not available", e);
        }
    }
}
