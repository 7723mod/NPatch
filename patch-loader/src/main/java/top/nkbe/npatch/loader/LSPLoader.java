package top.nkbe.npatch.loader;

import android.app.ActivityThread;
import android.app.Application;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dalvik.system.PathClassLoader;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.matrix.vector.impl.VectorContext;
import org.matrix.vector.impl.VectorLifecycleManager;
import org.matrix.vector.impl.core.VectorServiceClient;
import org.matrix.vector.nativebridge.NativeAPI;

public class LSPLoader {
    private static final String TAG = "NPatch-Loader";
    private static final Set<String> enhancedLoadedModules = new LinkedHashSet<>();

    public static void initModules(LoadedApk loadedApk) {
        installNativeModuleServiceProxy();
        XposedInit.loadModules(ActivityThread.currentActivityThread());
        dispatchModernLifecycle(loadedApk);

        XposedInit.loadedPackagesInProcess.add(loadedApk.getPackageName());
        setPackageNameForResDir(loadedApk.getPackageName(), loadedApk.getResDir());
        XC_LoadPackage.LoadPackageParam lpparam = new XC_LoadPackage.LoadPackageParam(
                XposedBridge.sLoadedPackageCallbacks);
        lpparam.packageName = loadedApk.getPackageName();
        lpparam.processName = ActivityThread.currentProcessName();
        lpparam.classLoader = loadedApk.getClassLoader();
        lpparam.appInfo = loadedApk.getApplicationInfo();
        lpparam.isFirstApplication = true;
        XC_LoadPackage.callAll(lpparam);
    }

    private static void installNativeModuleServiceProxy() {
        try {
            Field serviceField = VectorServiceClient.class.getDeclaredField("service");
            serviceField.setAccessible(true);

            Object current = serviceField.get(VectorServiceClient.INSTANCE);
            if (!(current instanceof ILSPApplicationService)) {
                Log.w(TAG, "VectorServiceClient service is not ready for native module proxy");
                return;
            }
            if (current instanceof NativeModuleFilteringService) {
                return;
            }

            serviceField.set(VectorServiceClient.INSTANCE,
                    new NativeModuleFilteringService((ILSPApplicationService) current));
            Log.i(TAG, "Installed native module service proxy");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to install native module service proxy", e);
        }
    }

    private static final class NativeModuleFilteringService extends ILSPApplicationService.Stub {
        private final ILSPApplicationService base;

        private NativeModuleFilteringService(ILSPApplicationService base) {
            this.base = base;
        }

        @Override
        public boolean isLogMuted() throws RemoteException {
            return base.isLogMuted();
        }

        @Override
        public List<Module> getLegacyModulesList() throws RemoteException {
            return base.getLegacyModulesList();
        }

        @Override
        public List<Module> getModulesList() throws RemoteException {
            List<Module> modules = base.getModulesList();
            if (modules == null || modules.isEmpty()) {
                return modules;
            }

            List<Module> filtered = new ArrayList<>(modules.size());
            String processName = ActivityThread.currentProcessName();
            for (Module module : modules) {
                if (!shouldHandleNative(module)) {
                    filtered.add(module);
                    continue;
                }

                String key = module.packageName + "@" + module.apkPath;
                synchronized (enhancedLoadedModules) {
                    if (enhancedLoadedModules.contains(key)) {
                        Log.i(TAG, "Filtering already enhanced native module: " + module.packageName);
                        continue;
                    }
                }

                Log.i(TAG, "Enhanced loading native module before core: " + module.packageName);
                if (performEnhancedLoad(module, false, processName)) {
                    synchronized (enhancedLoadedModules) {
                        enhancedLoadedModules.add(key);
                    }
                } else {
                    filtered.add(module);
                }
            }
            return filtered;
        }

        @Override
        public String getPrefsPath(String packageName) throws RemoteException {
            return base.getPrefsPath(packageName);
        }

        @Override
        public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder)
                throws RemoteException {
            return base.requestInjectedManagerBinder(binder);
        }

        @Override
        public IBinder asBinder() {
            return base.asBinder();
        }
    }

    private static boolean shouldHandleNative(Module module) {
        try (ZipFile zip = new ZipFile(new File(module.apkPath))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("lib/") && entry.getName().endsWith(".so")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean performEnhancedLoad(Module module, boolean isSystemServer, String processName) {
        try {
            File nativeDir = prepareNativeLibraryDir(module);
            String librarySearchPath = buildLibrarySearchPath(module, nativeDir);

            ClassLoader initLoader = XposedModule.class.getClassLoader();
            PathClassLoader moduleClassLoader = new PathClassLoader(module.apkPath, librarySearchPath, initLoader);

            ApplicationInfo moduleAppInfo = module.applicationInfo;
            if (moduleAppInfo == null) {
                moduleAppInfo = new ApplicationInfo();
                moduleAppInfo.packageName = module.packageName;
                moduleAppInfo.sourceDir = module.apkPath;
                moduleAppInfo.publicSourceDir = module.apkPath;
                moduleAppInfo.uid = module.appId;
            }
            if (nativeDir != null) {
                moduleAppInfo.nativeLibraryDir = nativeDir.getAbsolutePath();
                moduleAppInfo.flags |= ApplicationInfo.FLAG_HAS_CODE | (1 << 26);
            }

            VectorContext vectorContext = new VectorContext(
                    module.packageName,
                    moduleAppInfo,
                    module.service != null ? module.service : getEmptyService()
            );

            for (String libName : discoverNativeLibraries(module)) {
                if (module.file != null
                        && module.file.moduleLibraryNames != null
                        && module.file.moduleLibraryNames.contains(libName)) {
                    NativeAPI.recordNativeEntrypoint(libName);
                }
                for (String candidate : buildNativeInitCandidates(module, nativeDir, libName)) {
                    if (NativeAPI.initializeNativeEntrypoint(libName, candidate)) {
                        Log.i(TAG, "Prepared native library " + libName + " from " + candidate);
                        break;
                    }
                }
            }

            if (module.file != null && module.file.moduleClassNames != null) {
                for (String className : module.file.moduleClassNames) {
                    Class<?> moduleClass = moduleClassLoader.loadClass(className);
                    if (XposedModule.class.isAssignableFrom(moduleClass)) {
                        Constructor<?> ctor = moduleClass.getDeclaredConstructor();
                        ctor.setAccessible(true);
                        XposedModule instance = (XposedModule) ctor.newInstance();

                        instance.attachFramework(vectorContext);

                        VectorLifecycleManager.INSTANCE.getActiveModules().add(instance);

                        instance.onModuleLoaded(new XposedModuleInterface.ModuleLoadedParam() {
                            @Override public boolean isSystemServer() { return isSystemServer; }
                            @Override public String getProcessName() { return processName; }
                        });
                    }
                }
            }

            Log.d(TAG, "Enhanced load successful for " + module.packageName);
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Enhanced load failed for " + module.packageName, e);
            return false;
        }
    }

    private static File prepareNativeLibraryDir(Module module) {
        try {
            Application app = currentApplication();
            if (app == null) return null;
            
            File cacheRoot = app.getCacheDir();
            File moduleRoot = new File(new File(cacheRoot, "npatch/native"), module.packageName.replace(".", "_"));
            File apkFile = new File(module.apkPath);
            String stamp = apkFile.lastModified() + "-" + apkFile.length();
            File targetDir = new File(moduleRoot, stamp);
            
            if (targetDir.exists() && targetDir.list() != null && targetDir.list().length > 0) {
                return targetDir;
            }

            targetDir.mkdirs();
            String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            
            try (ZipFile zip = new ZipFile(apkFile)) {
                for (String abi : abis) {
                    String prefix = "lib/" + abi + "/";
                    boolean extractedAny = false;
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (entry.getName().startsWith(prefix) && entry.getName().endsWith(".so")) {
                            File outFile = new File(targetDir, new File(entry.getName()).getName());
                            try (InputStream is = zip.getInputStream(entry);
                                 FileOutputStream os = new FileOutputStream(outFile)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) > 0) os.write(buffer, 0, len);
                            }
                            outFile.setExecutable(true, false);
                            extractedAny = true;
                        }
                    }
                    if (extractedAny) return targetDir;
                }
            }
            return targetDir;
        } catch (Throwable e) {
            Log.e(TAG, "Failed to prepare native dir", e);
            return null;
        }
    }

    private static String buildLibrarySearchPath(Module module, File nativeDir) {
        StringBuilder sb = new StringBuilder();
        if (nativeDir != null) {
            sb.append(nativeDir.getAbsolutePath()).append(File.pathSeparator);
        }
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            sb.append(module.apkPath).append("!/lib/").append(abi).append(File.pathSeparator);
        }
        return sb.toString();
    }

    private static List<String> buildNativeInitCandidates(Module module, File nativeDir, String libName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (nativeDir != null) {
            candidates.add(new File(nativeDir, libName).getAbsolutePath());
        }
        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : abis) {
            candidates.add(module.apkPath + "!/lib/" + abi + "/" + libName);
            String normalizedAbi = abi.toLowerCase(Locale.ROOT);
            if (!normalizedAbi.equals(abi)) {
                candidates.add(module.apkPath + "!/lib/" + normalizedAbi + "/" + libName);
            }
        }
        return new ArrayList<>(candidates);
    }

    private static List<String> discoverNativeLibraries(Module module) {
        LinkedHashSet<String> libraries = new LinkedHashSet<>();
        if (module.file != null && module.file.moduleLibraryNames != null) {
            libraries.addAll(module.file.moduleLibraryNames);
        }

        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        try (ZipFile zip = new ZipFile(new File(module.apkPath))) {
            for (String abi : abis) {
                String prefix = "lib/" + abi + "/";
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".so")) {
                        libraries.add(new File(name).getName());
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to discover native libraries for " + module.packageName, e);
        }
        return new ArrayList<>(libraries);
    }

    private static Application currentApplication() {
        try {
            return (Application) XposedHelpers.callStaticMethod(
                Class.forName("android.app.ActivityThread"), "currentApplication");
        } catch (Throwable ignored) { return null; }
    }

    private static org.lsposed.lspd.service.ILSPInjectedModuleService getEmptyService() {
        try {
            Class<?> clazz = Class.forName("org.matrix.vector.impl.core.VectorModuleManager$EmptyInjectedModuleService");
            return (org.lsposed.lspd.service.ILSPInjectedModuleService) XposedHelpers.getStaticObjectField(clazz, "INSTANCE");
        } catch (Throwable ignored) { return null; }
    }

    private static void dispatchModernLifecycle(LoadedApk loadedApk) {
        try {
            String packageName = loadedApk.getPackageName();
            ApplicationInfo appInfo = loadedApk.getApplicationInfo();
            ClassLoader classLoader = loadedApk.getClassLoader();
            Object appComponentFactory = createAppComponentFactory(appInfo, classLoader);

            VectorLifecycleManager.INSTANCE.dispatchPackageLoaded(
                    packageName,
                    appInfo,
                    true,
                    classLoader);
            VectorLifecycleManager.INSTANCE.dispatchPackageReady(
                    packageName,
                    appInfo,
                    true,
                    classLoader,
                    classLoader,
                    appComponentFactory);
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
