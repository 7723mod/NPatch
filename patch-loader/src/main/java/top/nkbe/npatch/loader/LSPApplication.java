package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.CONFIG_ASSET_PATH;
import static top.nkbe.npatch.share.Constants.PROVIDER_DEX_ASSET_PATH;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.Process;
import android.system.Os;
import android.util.Log;
import android.widget.Toast;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;
import org.matrix.vector.ipc.LoadedModule;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.Startup;
import top.nkbe.npatch.loader.util.XLog;
import top.nkbe.npatch.service.IntegrApplicationService;
import top.nkbe.npatch.service.NeoLocalApplicationService;
import top.nkbe.npatch.service.RemoteApplicationService;
import top.nkbe.npatch.share.Constants;
import top.nkbe.npatch.share.PatchConfig;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import android.os.IBinder;
import java.lang.reflect.Method;
import org.matrix.vector.impl.core.VectorModuleManager;
import top.nkbe.npatch.service.EmbeddedXposedService;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hidden.HiddenApiBridge;

/**
 * Created by Windysha
 * Updated by NkBe
 */
@SuppressWarnings("unused")
public class LSPApplication {

    private static final String TAG = "NPatch";
    private static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    private static final int PER_USER_RANGE = 100000;

    private static final Gson GSON = new Gson();

    private static ActivityThread activityThread;
    private static LoadedApk stubLoadedApk;
    private static LoadedApk appLoadedApk;
    private static Thread.UncaughtExceptionHandler previousUncaughtExceptionHandler;
    private static boolean crashInterceptorInstalled;
    private static boolean outputLoggingConfigured;
    private static volatile Throwable lastCoreCapturedCrash;

    private static PatchConfig config;
    private static Path pendingProviderPath;

    private static void logInfo(String msg) {
        XLog.i(TAG, msg);
    }

    private static void logWarn(String msg) {
        XLog.w(TAG, msg);
    }

    private static void setPathField(ApplicationInfo appInfo, String fieldName, String value) {
        if (appInfo == null || value == null) return;
        try {
            XposedHelpers.setObjectField(appInfo, fieldName, value);
        } catch (Throwable ignored) {
        }
    }

    private static void restoreVisibleApplicationInfo(Object boundApplication, ApplicationInfo appInfo, String visibleApkPath) {
        if (appInfo == null || visibleApkPath == null) return;

        appInfo.sourceDir = visibleApkPath;
        appInfo.publicSourceDir = visibleApkPath;
        setPathField(appInfo, "scanSourceDir", visibleApkPath);
        setPathField(appInfo, "scanPublicSourceDir", visibleApkPath);

        if (boundApplication != null) {
            try {
                XposedHelpers.setObjectField(boundApplication, "appInfo", appInfo);
            } catch (Throwable ignored) {
            }
        }
        if (appLoadedApk != null) {
            try {
                XposedHelpers.setObjectField(appLoadedApk, "mApplicationInfo", appInfo);
            } catch (Throwable ignored) {
            }
        }
        if (stubLoadedApk != null) {
            try {
                XposedHelpers.setObjectField(stubLoadedApk, "mApplicationInfo", appInfo);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void restoreVisibleLoadedApkResources(LoadedApk loadedApk, String visibleApkPath) {
        if (loadedApk == null || visibleApkPath == null) return;

        setLoadedApkPathField(loadedApk, "mResDir", visibleApkPath);
        setLoadedApkPathArrayField(loadedApk, "mSplitResDirs", visibleApkPath, visibleApkPath);
    }

    private static void setLoadedApkPathField(LoadedApk loadedApk, String fieldName, String value) {
        try {
            XposedHelpers.setObjectField(loadedApk, fieldName, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setLoadedApkPathArrayField(LoadedApk loadedApk, String fieldName, String fromValue, String toValue) {
        try {
            Object value = XposedHelpers.getObjectField(loadedApk, fieldName);
            if (!(value instanceof String[] paths)) {
                return;
            }
            for (int i = 0; i < paths.length; i++) {
                if (paths[i] == null || paths[i].equals(fromValue)) {
                    paths[i] = toValue;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isIsolated() {
        return (Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    private static boolean hasEmbeddedModules(Context context) {
        try {
            String[] list = context.getAssets().list("npatch/modules");
            return list != null && list.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static int resolveSigBypassLevel(ApplicationInfo appInfo, int fallbackLevel) {
        if (appInfo == null || appInfo.packageName == null) {
            return fallbackLevel;
        }
        try {
            var systemContext = activityThread.getSystemContext();
            if (systemContext == null) {
                return fallbackLevel;
            }
            var packageManager = (PackageManager) XposedHelpers.callMethod(systemContext, "getPackageManager");
            var metaData = packageManager
                    .getApplicationInfo(appInfo.packageName, PackageManager.GET_META_DATA)
                    .metaData;
            String encoded = metaData == null ? null : metaData.getString("npatch");
            if (encoded == null) {
                return fallbackLevel;
            }
            String json = new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
            return new JSONObject(json).optInt("sigBypassLevel", fallbackLevel);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to resolve signature bypass level from manifest metadata", e);
            return fallbackLevel;
        }
    }

    private static void registerModuleCallerPrefixes(IFrameworkService service) {
        if (service == null) return;
        try {
            registerModuleCallerPrefixes(service.getLegacyModules());
            registerModuleCallerPrefixes(service.getModules());
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register LoadedModule caller prefixes", e);
        }
    }

    private static void registerModuleCallerPrefixes(List<LoadedModule> modules) {
        if (modules == null) return;
        for (LoadedModule LoadedModule : modules) {
            if (LoadedModule == null) continue;
            SigBypass.registerModuleCallerPrefix(LoadedModule.packageName);
            if (LoadedModule.code == null || LoadedModule.code.moduleClassNames == null) continue;
            for (String className : LoadedModule.code.moduleClassNames) {
                int lastDot = className == null ? -1 : className.lastIndexOf('.');
                if (lastDot > 0) {
                    SigBypass.registerModuleCallerPrefix(className.substring(0, lastDot));
                }
            }
        }
    }

    private static void exemptHiddenApi() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        boolean exempted = false;
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntimeClass.getDeclaredMethod("getRuntime");
            getRuntime.setAccessible(true);
            Object vmRuntime = getRuntime.invoke(null);
            Method setExemptions = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.setAccessible(true);
            setExemptions.invoke(vmRuntime, (Object) new String[]{"L"});
            exempted = true;
        } catch (Throwable ignored) {
        }

        if (!exempted) {
            try {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L");
            } catch (Throwable t) {
                try {
                    org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
                } catch (Throwable t2) {
                    Log.w(TAG, "Hidden API exemption in onLoad failed", t2);
                }
            }
        }
    }

    public static void onLoad() throws RemoteException, IOException {
        exemptHiddenApi();

        if (isIsolated()) {
            XLog.d(TAG, "Skip isolated process");
            return;
        }
        activityThread = ActivityThread.currentActivityThread();
        var context = createLoadedApkWithContext();
        if (context == null) {
            XLog.e(TAG, "Error when creating context");
            return;
        }
        String installedApkPath = context.getPackageCodePath();
        logInfo("Initialize service client");
        IFrameworkService service = null;

        if (config.useManager) {
            try {
                service = new RemoteApplicationService(context);
                List<LoadedModule> legacyModules = service.getLegacyModules();
                List<LoadedModule> modernModules = service.getModules();
                JSONArray moduleArr = new JSONArray();
                Map<String, String> cachedModules = new LinkedHashMap<>();

                if (legacyModules != null) {
                    for (LoadedModule LoadedModule : legacyModules) {
                        if (LoadedModule == null || LoadedModule.packageName == null || LoadedModule.apkPath == null) {
                            continue;
                        }
                        cachedModules.put(LoadedModule.packageName, LoadedModule.apkPath);
                    }
                }

                if (modernModules != null) {
                    for (LoadedModule LoadedModule : modernModules) {
                        if (LoadedModule == null || LoadedModule.packageName == null || LoadedModule.apkPath == null) {
                            continue;
                        }
                        cachedModules.put(LoadedModule.packageName, LoadedModule.apkPath);
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
                logInfo("Success update LoadedModule scope from Manager");
            } catch (Throwable e) {
                logWarn("Failed to connect to manager: " + e.getMessage());
                service = null;
            }
        }

        if (service == null) {
            if (hasEmbeddedModules(context)) {
                logInfo("Using Integrated Service (Embedded Modules Found)");
                service = new IntegrApplicationService(context);
            } else {
                logInfo("Using NeoLocal Service (Cached Config)");
                service = new NeoLocalApplicationService(context);
            }
        }

        ClassLoader frameworkLoader = XposedBridge.class.getClassLoader();
        if (frameworkLoader != null && frameworkLoader.getParent() != null) {
            XposedBridge.dummyClassLoader = frameworkLoader.getParent();
        }

        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed(false);

        // Track appLoadedApk so its modern + legacy package lifecycle is driven
        // exactly once when realizeLoadedApk() builds the class loader below.
        Startup.trackLoadedApk(appLoadedApk);

        logInfo("Load modules");
        LSPLoader.initModules(appLoadedApk);
        logInfo("Modules initialized");

        registerModuleCallerPrefixes(service);
        SigBypass.registerModuleNativeLibraryRoots(context);
        SigBypass.doSigBypass(context, config.lspConfig.sigBypassLevel, config.hideLibs);
        disableProfile(context);

        // Realize the target's class loader now that hooks, modules and signature bypass are all armed.
        // getClassLoader() -> createOrUpdateClassLoaderLocked -> createAppFactory triggers
        // onPackageLoaded (pre-<clinit>) then, on return, onPackageReady and legacy handleLoadPackage.
        realizeLoadedApk(installedApkPath);

        if (!config.useManager) {
            for (String modulePkg : VectorModuleManager.INSTANCE.loadedModulePackages()) {
                try {
                    ClassLoader moduleCl = VectorModuleManager.INSTANCE.getModuleClassLoader(modulePkg);
                    if (moduleCl == null) continue;
                    Class<?> helper = moduleCl.loadClass("io.github.libxposed.service.XposedServiceHelper");
                    if (helper.getClassLoader() == EmbeddedXposedService.class.getClassLoader()) {
                        continue;
                    }
                    var stub = new EmbeddedXposedService(context, modulePkg, config.newPackage);
                    Method onBinderReceived = helper.getDeclaredMethod("onBinderReceived", IBinder.class);
                    onBinderReceived.setAccessible(true);
                    onBinderReceived.invoke(null, stub.asBinder());
                    Log.i(TAG, "Delivered XposedService to embedded module " + modulePkg);
                } catch (ClassNotFoundException ignored) {
                } catch (Throwable t) {
                    Log.w(TAG, "XposedService delivery failed for " + modulePkg, t);
                }
            }
        }
        try {
            CacheCleaner.sweepModuleNativeCache(context.getApplicationInfo(), LSPLoader.getActiveModuleApkPaths());
        } catch (Throwable e) {
            Log.w(TAG, "Failed to sweep LoadedModule native cache", e);
        }

        switchAllClassLoader();

        if (config.useMicroG) {
            logInfo("Activating MicroG redirect via NPatch");
            GmsRedirector.activate(context, config.originalSignature);
        }

        logInfo("NPatch bootstrap completed");
    }

    private static void installCrashInterceptor(Context context) {
        if (crashInterceptorInstalled) {
            return;
        }

        crashInterceptorInstalled = true;
        previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                if (lastCoreCapturedCrash != throwable) {
                    XLog.e(TAG, "Uncaught exception in " + thread.getName(), throwable);
                }
                lastCoreCapturedCrash = null;
                if (context != null) {
                    new Handler(Looper.getMainLooper()).post(() ->
                            Toast.makeText(
                                    context.getApplicationContext(),
                                    "Crash log saved to Media directory",
                                    Toast.LENGTH_LONG
                            ).show()
                    );
                }
            } catch (Throwable ignored) {
            }

            if (previousUncaughtExceptionHandler != null) {
                previousUncaughtExceptionHandler.uncaughtException(thread, throwable);
            }
        });
    }

    private static synchronized void configureOutputLogging(Context context) {
        if (outputLoggingConfigured || config == null || !config.outputLog) {
            return;
        }
        outputLoggingConfigured = true;
        installCrashInterceptor(context);
    }

    private static Context createLoadedApkWithContext() {
        try {
            var timeStart = System.currentTimeMillis();
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");

            stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");
            CompatibilityInfo compatInfo = null;
            try {
                compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(mBoundApplication, "compatInfo");
            } catch (Throwable ignored) {
            }
            if (compatInfo == null) {
                try {
                    compatInfo = (CompatibilityInfo) XposedHelpers.getStaticObjectField(
                            CompatibilityInfo.class, "DEFAULT_COMPATIBILITY_INFO");
                } catch (Throwable ignored) {
                }
            }
            var baseClassLoader = stubLoadedApk.getClassLoader();
            String installedApkPath = appInfo.sourceDir;

            try (var is = baseClassLoader.getResourceAsStream(CONFIG_ASSET_PATH)) {
                if (is == null) throw new IOException("Config file not found in assets");
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                config = GSON.fromJson(streamReader, PatchConfig.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            // Keep the effective bypass level out of the editable config.json copy.
            config.lspConfig.sigBypassLevel = resolveSigBypassLevel(appInfo, config.sigBypassLevel);
            XLog.init(config.newPackage, ActivityThread.currentProcessName(), config.outputLog);
            configureOutputLogging(null);
            logInfo("Loaded patch config for " + config.newPackage + ", useManager=" + config.useManager + ", outputLog=" + config.outputLog);
            Log.i(TAG, "Use manager: " + config.useManager);
            Log.i(TAG, "Signature bypass level: " + config.lspConfig.sigBypassLevel);

            CacheCleaner.handlePatchUpgrade(appInfo, installedApkPath);
            final ApplicationInfo appInfoRef = appInfo;
            Thread sweepThread = new Thread(() -> {
                CacheCleaner.sweepLibNpatchCache(appInfoRef);
                CacheCleaner.sweepLegacyNpatchCache(appInfoRef);
                CacheCleaner.sweepLegacyHostNativeCache(appInfoRef);
            }, "NPatch-Sweep");
            sweepThread.setDaemon(true);
            sweepThread.start();

            String loadedApkSourceDir = installedApkPath;
            boolean loadedApkUsesOriginCache = false;
            if (config.lspConfig.sigBypassLevel >= Constants.SIGBYPASS_BASIC) {
                Path cacheApkPath = OriginApkHelper.prepareOriginApk(appInfo, baseClassLoader);
                SigBypass.setPaths(cacheApkPath.toString(), installedApkPath);
                SigBypass.setOriginalSignature(config.newPackage, config.originalSignature);
                loadedApkSourceDir = cacheApkPath.toString();
                loadedApkUsesOriginCache = true;
                XLog.i(TAG, "LoadedApk source mode=cache"
                        + ", installedApkPath=" + installedApkPath
                        + ", cacheApkPath=" + cacheApkPath
                        + ", selected=" + loadedApkSourceDir);
                try {
                    long originCrc = OriginApkHelper.getOriginalApkCrc(installedApkPath);
                    if (originCrc > 0) {
                        CacheCleaner.sweepOriginApkCache(appInfo, originCrc);
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed to sweep origin apk cache", e);
                }
            }
            // The patched manifest points appComponentFactory at the metaloader stub. appInfo has to
            // be handed back whatever the ORIGINAL apk declared, and appLoadedApk is built from it a
            // few lines below, so that decision has to be made here -- before the app's own class
            // loader exists and before its factory's <clinit> can run.
            appInfo.appComponentFactory = resolveOriginalAppComponentFactory(loadedApkSourceDir);

            if (config.injectProvider) {
                Path providerDir = Paths.get(appInfo.dataDir, "cache/code_cache/");
                if (!Files.exists(providerDir)) Files.createDirectories(providerDir);
                Path providerPath = providerDir.resolve("provider.dex");
                try {
                    Files.deleteIfExists(providerPath);
                    try (InputStream is = baseClassLoader.getResourceAsStream(PROVIDER_DEX_ASSET_PATH)) {
                        if (is != null) Files.copy(is, providerPath);
                    }
                    if (Files.exists(providerPath)) {
                        providerPath.toFile().setWritable(false);
                        pendingProviderPath = providerPath;
                    } else {
                        pendingProviderPath = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to inject provider:" + Log.getStackTraceString(e));
                    pendingProviderPath = null;
                }
            }

            var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mPackages");
            mPackages.remove(appInfo.packageName);
            appInfo.sourceDir = loadedApkSourceDir;
            appInfo.publicSourceDir = loadedApkSourceDir;
            appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);

            // LoadedApk resources must remain paired with the APK used to create it.  In
            // signature-bypass mode that APK is the cached original APK; replacing mResDir
            // with the patched APK mixes its resource table with the original app's IDs and
            // causes Resources$NotFoundException while inflating layouts.
            if (!loadedApkUsesOriginCache) {
                restoreVisibleLoadedApkResources(appLoadedApk, installedApkPath);
                restoreVisibleApplicationInfo(mBoundApplication, appInfo, installedApkPath);
            }
            XposedHelpers.setObjectField(mBoundApplication, "info", appLoadedApk);

            // The class loader is deliberately NOT built here. Building it runs the app's
            // AppComponentFactory <clinit>, which a packed app can turn into a native anti-tamper gate;
            // it must not run until the LoadedApk hooks, modules and signature bypass are armed. onLoad
            // arms them and then calls realizeLoadedApk().
            Log.i(TAG, "hooked app initialized: " + appLoadedApk);

            var context = (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", activityThread, stubLoadedApk);
            Log.i(TAG, "createLoadedApkWithContext cost: " + (System.currentTimeMillis() - timeStart) + "ms");
            return context;
        } catch (Throwable e) {
            Log.e(TAG, "createLoadedApkWithContext failed", e);
            XLog.e(TAG, "createLoadedApk", e);
            return null;
        }
    }

    /**
     * Builds the target app's class loader, now that the LoadedApk hooks, modules and signature bypass
     * are armed. This is the point where the app's AppComponentFactory is instantiated and its
     * <clinit> runs; the createAppFactory hook fires onPackageLoaded immediately before that, and
     * the createOrUpdateClassLoaderLocked hook fires onPackageReady and the legacy handleLoadPackage
     * on return. It then repoints any ActivityClientRecord still holding the stub LoadedApk at the real one.
     */
    private static void realizeLoadedApk(String installedApkPath) {
        ClassLoader loader = appLoadedApk.getClassLoader();

        appendHostNativeLibraryPaths(loader, installedApkPath);

        if (config.injectProvider && pendingProviderPath != null) {
            try {
                Object dexPathList = XposedHelpers.getObjectField(loader, "pathList");
                Object dexElements = XposedHelpers.getObjectField(dexPathList, "dexElements");
                int length = Array.getLength(dexElements);
                Object newElements = Array.newInstance(dexElements.getClass().getComponentType(), length + 1);
                System.arraycopy(dexElements, 0, newElements, 0, length);

                Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
                Object dexFile = dexFileClass.getConstructor(String.class).newInstance(pendingProviderPath.toString());
                Class<?> elementClass = Class.forName("dalvik.system.DexPathList$Element");
                Object element = elementClass.getConstructor(dexFileClass).newInstance(dexFile);
                Array.set(newElements, length, element);
                XposedHelpers.setObjectField(dexPathList, "dexElements", newElements);
            } catch (Throwable e) {
                Log.e(TAG, "Failed to inject provider dex: " + e.getMessage(), e);
            }
        }

        var activityClientRecordClass = XposedHelpers.findClass("android.app.ActivityThread$ActivityClientRecord", ActivityThread.class.getClassLoader());
        var fixActivityClientRecord = (BiConsumer<Object, Object>) (k, v) -> {
            if (activityClientRecordClass.isInstance(v)) {
                var pkgInfo = XposedHelpers.getObjectField(v, "packageInfo");
                if (pkgInfo == stubLoadedApk) {
                    Log.d(TAG, "fix loadedapk from ActivityClientRecord");
                    XposedHelpers.setObjectField(v, "packageInfo", appLoadedApk);
                }
            }
        };
        var mActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mActivities");
        mActivities.forEach(fixActivityClientRecord);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                var mLaunchingActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mLaunchingActivities");
                mLaunchingActivities.forEach(fixActivityClientRecord);
            }
        } catch (Throwable ignored) {
        }
    }


    /**
     * Decides what {@code ApplicationInfo.appComponentFactory} must be for the app's own class loader.
     *
     * <p>The patched manifest replaces the app's factory with the metaloader stub, so this has to put
     * back whatever the original declared. Two things make that less obvious than it looks:
     *
     * <ul>
     *   <li>The declaration must be <b>probed in the apk that actually holds the app's code</b>. The
     *       stub's own class loader can never resolve a class belonging to the app, so probing there
     *       reported every declaration as missing and silently stripped the factory from every app
     *       that declared one -- the framework then fell back to its default factory.
     *   <li>The probe must not build the app's class loader. A throwaway loader is used, and
     *       {@code loadClass} resolves a class <i>without</i> running its static initializer, so a
     *       factory whose {@code <clinit>} is an anti-tamper gate still waits for
     *       {@link #realizeLoadedApk()}.
     * </ul>
     *
     * @param apkPath the apk the app's class loader will be built from: the cached original in
     *     signature-bypass mode, otherwise the patched apk itself.
     * @return the original factory's name, or {@code null} when the app declared none -- or declared
     *     one it does not ship, which is dropped the same way so the manifest's stub is never left in
     *     place.
     */
    private static String resolveOriginalAppComponentFactory(String apkPath) {
        String declared = config.appComponentFactory;
        if (declared == null || declared.isEmpty()) {
            Log.i(TAG, "Original app declared no AppComponentFactory; clearing the stub");
            return null;
        }
        if (appApkHasClass(apkPath, declared)) {
            Log.i(TAG, "Restored original AppComponentFactory: " + declared);
            return declared;
        }
        Log.w(TAG, "Original AppComponentFactory not found in " + apkPath + ": " + declared);
        return null;
    }

    /**
     * Whether {@code apkPath} ships {@code className}, without building the app's class loader.
     *
     * <p>The check reads the apk's dex headers directly instead of building a class loader over it.
     * On Android 16 a {@code PathClassLoader} over an apk in app-private storage is refused outright
     * -- ART answers "Writable dex file ... is not allowed" -- so a loader-based probe could never
     * answer on that platform and every declaration would take the conservative branch below.
     *
     * <p>A throwaway loader also had to be avoided for a second reason: it resolves real classes,
     * which would drag in whatever the factory references. Reading the dex string pool touches no
     * class at all, so a factory whose {@code <clinit>} is an anti-tamper gate still waits for
     * {@link #realizeLoadedApk()}.
     *
     * <p>A probe that cannot answer reports {@code true}, keeping the declaration and leaving the
     * decision to the framework, which falls back to its default factory by itself when the class
     * proves unusable.
     */
    private static boolean appApkHasClass(String apkPath, String className) {
        if (apkPath == null || apkPath.isEmpty()) {
            return false;
        }
        // ART resolves a class through its type descriptor, so that is what the pool holds.
        String descriptor = "L" + className.replace('.', '/') + ";";
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apkPath)) {
            Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.endsWith(".dex")) {
                    continue;
                }
                try (InputStream is = zip.getInputStream(entry)) {
                    if (streamContainsBytes(is, descriptor.getBytes(StandardCharsets.UTF_8))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "AppComponentFactory probe failed: " + className, t);
            return true;
        }
    }

    /**
     * Whether {@code pattern} appears in the stream, scanning with a sliding window so a large dex
     * is never held in memory in full.
     */
    private static boolean streamContainsBytes(InputStream is, byte[] pattern) throws IOException {
        int patternLen = pattern.length;
        if (patternLen == 0) {
            return false;
        }
        byte[] buffer = new byte[64 * 1024];
        int carry = 0;
        int read;
        while ((read = is.read(buffer, carry, buffer.length - carry)) != -1) {
            int available = carry + read;
            int limit = available - patternLen;
            for (int i = 0; i <= limit; i++) {
                int j = 0;
                while (j < patternLen && buffer[i + j] == pattern[j]) {
                    j++;
                }
                if (j == patternLen) {
                    return true;
                }
            }
            // Keep the last patternLen-1 bytes so a match straddling reads is not missed.
            carry = Math.min(patternLen - 1, available);
            if (carry > 0) {
                System.arraycopy(buffer, available - carry, buffer, 0, carry);
            }
        }
        return false;
    }

    private static void appendHostNativeLibraryPaths(ClassLoader loader, String installedApkPath) {
        if (loader == null || installedApkPath == null || installedApkPath.isEmpty()) return;
        List<String> libPaths = new ArrayList<>();
        try {
            String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            for (String abi : abis) {
                libPaths.add(installedApkPath + "!/lib/" + abi);
            }

            // Method 1: PathClassLoader/BaseDexClassLoader.addNativePath(Collection<String>)
            try {
                Method method = loader.getClass().getMethod("addNativePath", Collection.class);
                method.setAccessible(true);
                method.invoke(loader, libPaths);
                Log.i(TAG, "Appended host native library paths via ClassLoader.addNativePath: " + libPaths);
                return;
            } catch (Throwable ignored) {
            }

            // Method 2: DexPathList.addNativePath(Collection<String>) (AOSP 7.0+)
            Object dexPathList = XposedHelpers.getObjectField(loader, "pathList");
            if (dexPathList != null) {
                Method addNativePathMethod = dexPathList.getClass().getDeclaredMethod("addNativePath", Collection.class);
                addNativePathMethod.setAccessible(true);
                addNativePathMethod.invoke(dexPathList, libPaths);
                Log.i(TAG, "Appended host native library paths via DexPathList.addNativePath: " + libPaths);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to append host native library paths: " + libPaths, e);
        }
    }

    public static void disableProfile(Context context) {
        try {
            var appInfo = context.getApplicationInfo();
            if (appInfo == null) return;

            var codePaths = new ArrayList<String>();
            if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) codePaths.add(appInfo.sourceDir);
            if (appInfo.splitSourceDirs != null) Collections.addAll(codePaths, appInfo.splitSourceDirs);
            if (codePaths.isEmpty()) return;

            File profileDir = null;
            try {
                profileDir = (File) XposedHelpers.callStaticMethod(
                        android.os.Environment.class, "getDataProfilesDePackageDirectory",
                        appInfo.uid / PER_USER_RANGE, context.getPackageName());
            } catch (Throwable e) {
                Log.w(TAG, "Failed to get profile dir", e);
                return;
            }

            for (int i = codePaths.size() - 1; i >= 0; i--) {
                String splitName = (i == 0 || appInfo.splitNames == null || i - 1 >= appInfo.splitNames.length)
                        ? null
                        : appInfo.splitNames[i - 1];
                File profile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof");

                try {
                    // ������� 0 �ֹ���Ψ�x��ֱ�����^
                    if (profile.exists() && profile.length() == 0 && !profile.canWrite()) continue;
                    // �Ԅӌ��Ѵ��ڵęn��������ջ����n
                    try (var ignored = new FileOutputStream(profile)) {
                    }
                    // �O���n��ֻ�x
                    Os.chmod(profile.getAbsolutePath(), 00444);

                } catch (Throwable e) {
                    Log.e(TAG, "Failed to disable profile: " + profile.getName(), e);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "Failed to disable profile completely", e);
        }
    }

    private static void switchAllClassLoader() {
        var fields = LoadedApk.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() == ClassLoader.class) {
                var obj = XposedHelpers.getObjectField(appLoadedApk, field.getName());
                XposedHelpers.setObjectField(stubLoadedApk, field.getName(), obj);
            }
        }
    }
}
