package org.lsposed.npatch.loader;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.matrix.vector.impl.VectorLifecycleManager;

public class LSPLoader {
    private static final String TAG = "NPatch";

    public static void initModules(LoadedApk loadedApk) {
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
            Log.w(TAG, "XResources.setPackageNameForResDir not available, skipping resource dir setup", e);
        }
    }
}
