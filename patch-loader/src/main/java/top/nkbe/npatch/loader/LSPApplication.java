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
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.matrix.vector.Startup;
import org.matrix.vector.impl.VectorLogBridge;
import top.nkbe.npatch.loader.util.XLog;
import top.nkbe.npatch.service.IntegrApplicationService;
import top.nkbe.npatch.service.NeoLocalApplicationService;
import top.nkbe.npatch.service.RemoteApplicationService;
import top.nkbe.npatch.share.Constants;
import top.nkbe.npatch.share.PatchConfig;
import top.nkbe.npatch.util.SB;

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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

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

    private static void registerModuleCallerPrefixes(ILSPApplicationService service) {
        if (service == null) return;
        try {
            registerModuleCallerPrefixes(service.getLegacyModulesList());
            registerModuleCallerPrefixes(service.getModulesList());
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register module caller prefixes", e);
        }
    }

    private static void registerModuleCallerPrefixes(List<Module> modules) {
        if (modules == null) return;
        for (Module module : modules) {
            if (module == null) continue;
            SigBypass.registerModuleCallerPrefix(module.packageName);
            if (module.file == null || module.file.moduleClassNames == null) continue;
            for (String className : module.file.moduleClassNames) {
                int lastDot = className == null ? -1 : className.lastIndexOf('.');
                if (lastDot > 0) {
                    SigBypass.registerModuleCallerPrefix(className.substring(0, lastDot));
                }
            }
        }
    }

    public static void onLoad() throws RemoteException, IOException {
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
        if (SB.hasConflict(context)) {
            SB.triggerConflict(context);
            return;
        }

        logInfo("Initialize service client");
        ILSPApplicationService service = null;

        if (config.useManager) {
            try {
                service = new RemoteApplicationService(context);
                List<Module> legacyModules = service.getLegacyModulesList();
                List<Module> modernModules = service.getModulesList();
                JSONArray moduleArr = new JSONArray();
                Map<String, String> cachedModules = new LinkedHashMap<>();

                if (legacyModules != null) {
                    for (Module module : legacyModules) {
                        if (module == null || module.packageName == null || module.apkPath == null) {
                            continue;
                        }
                        cachedModules.put(module.packageName, module.apkPath);
                    }
                }

                if (modernModules != null) {
                    for (Module module : modernModules) {
                        if (module == null || module.packageName == null || module.apkPath == null) {
                            continue;
                        }
                        cachedModules.put(module.packageName, module.apkPath);
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
                logInfo("Success update module scope from Manager");
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

        registerModuleCallerPrefixes(service);
        SigBypass.doSigBypass(context, config.lspConfig.sigBypassLevel, config.hideLibs);
        disableProfile(context);

        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed(false);

        // WARN: Since it uses `XResource`, the following class should not be initialized
        // before forkPostCommon is invoke. Otherwise, you will get failure of XResources

        logInfo("Load modules");
        LSPLoader.initModules(appLoadedApk);
        logInfo("Modules initialized");
        try {
            CacheCleaner.sweepModuleNativeCache(context.getApplicationInfo(), LSPLoader.getActiveModuleApkPaths());
        } catch (Throwable e) {
            Log.w(TAG, "Failed to sweep module native cache", e);
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
        XposedLogPrinter printer = new XposedLogPrinter(0, "NPatch");
        XposedBridge.setLogPrinter(printer);
        VectorLogBridge.setSink((priority, tag, message, throwable) -> {
            if ("NPatchCrash".equals(tag) && throwable != null) {
                lastCoreCapturedCrash = throwable;
            }
            XposedLogPrinter.log(priority, tag, message, throwable);
        });
        installCrashInterceptor(context);
    }

    private static Context createLoadedApkWithContext() {
        try {
            var timeStart = System.currentTimeMillis();
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");

            stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");
            var compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(mBoundApplication, "compatInfo");
            var baseClassLoader = stubLoadedApk.getClassLoader();
            String patchedApkPath = appInfo.sourceDir;

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

            CacheCleaner.handlePatchUpgrade(appInfo, patchedApkPath);
            CacheCleaner.sweepLibNpatchCache(appInfo);
            CacheCleaner.sweepLegacyNpatchCache(appInfo);

            String loadedApkSourceDir = patchedApkPath;
            boolean loadedApkUsesOriginCache = false;
            if (config.lspConfig.sigBypassLevel >= Constants.SIGBYPASS_BASIC) {
                Path cacheApkPath = OriginApkHelper.prepareOriginApk(appInfo, baseClassLoader);
                Path nativeLibraryDir = OriginApkHelper.prepareNativeLibraryDir(appInfo, cacheApkPath, patchedApkPath);
                SigBypass.setPaths(cacheApkPath.toString(), patchedApkPath);
                SigBypass.setOriginalSignature(config.newPackage, config.originalSignature);
                loadedApkSourceDir = cacheApkPath.toString();
                loadedApkUsesOriginCache = true;
                XLog.i(TAG, "LoadedApk source mode=cache"
                        + ", patchedApkPath=" + patchedApkPath
                        + ", cacheApkPath=" + cacheApkPath
                        + ", selected=" + loadedApkSourceDir);
                if (nativeLibraryDir != null) {
                    appInfo.nativeLibraryDir = nativeLibraryDir.toString();
                }
                try {
                    CacheCleaner.sweepOriginApkCache(appInfo, OriginApkHelper.getOriginalApkCrc(patchedApkPath));
                } catch (IOException e) {
                    Log.w(TAG, "Failed to sweep origin apk cache", e);
                }
            }
            if (config.lspConfig.sigBypassLevel >= Constants.SIGBYPASS_HIGH) {
                appInfo.appComponentFactory = config.appComponentFactory;
            } else {
                appInfo.appComponentFactory = null;
            }

            Path providerPath = null;
            if (config.injectProvider) {
                Path providerDir = Paths.get(appInfo.dataDir, "cache/code_cache/");
                if (!Files.exists(providerDir)) Files.createDirectories(providerDir);
                providerPath = providerDir.resolve("provider.dex");
                try {
                    Files.deleteIfExists(providerPath);
                    try (InputStream is = baseClassLoader.getResourceAsStream(PROVIDER_DEX_ASSET_PATH)) {
                        if (is != null) Files.copy(is, providerPath);
                    }
                    if (Files.exists(providerPath)) {
                        providerPath.toFile().setWritable(false);
                    } else {
                        providerPath = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to inject provider:" + Log.getStackTraceString(e));
                    providerPath = null;
                }
            }

            var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mPackages");
            mPackages.remove(appInfo.packageName);
            appInfo.sourceDir = loadedApkSourceDir;
            appInfo.publicSourceDir = loadedApkSourceDir;
            appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);
            appLoadedApk.getClassLoader();
            // LoadedApk resources must remain paired with the APK used to create it.  In
            // signature-bypass mode that APK is the cached original APK; replacing mResDir
            // with the patched APK mixes its resource table with the original app's IDs and
            // causes Resources$NotFoundException while inflating layouts.
            if (!loadedApkUsesOriginCache) {
                restoreVisibleLoadedApkResources(appLoadedApk, patchedApkPath);
            }

            if (config.injectProvider && providerPath != null) {
                try {
                    ClassLoader loader = appLoadedApk.getClassLoader();
                    Object dexPathList = XposedHelpers.getObjectField(loader, "pathList");
                    Object dexElements = XposedHelpers.getObjectField(dexPathList, "dexElements");
                    int length = Array.getLength(dexElements);
                    Object newElements = Array.newInstance(dexElements.getClass().getComponentType(), length + 1);
                    System.arraycopy(dexElements, 0, newElements, 0, length);

                    // Use reflection for DexFile to handle deprecation on Android 14+
                    Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
                    Object dexFile = dexFileClass.getConstructor(String.class).newInstance(providerPath.toString());
                    Class<?> elementClass = Class.forName("dalvik.system.DexPathList$Element");
                    Object element = elementClass.getConstructor(dexFileClass).newInstance(dexFile);
                    Array.set(newElements, length, element);
                    XposedHelpers.setObjectField(dexPathList, "dexElements", newElements);
                } catch (Throwable e) {
                    Log.e(TAG, "Failed to inject provider dex: " + e.getMessage(), e);
                }
            }

            restoreVisibleApplicationInfo(mBoundApplication, appInfo, patchedApkPath);
            XposedHelpers.setObjectField(mBoundApplication, "info", appLoadedApk);

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
            Log.i(TAG, "hooked app initialized: " + appLoadedApk);

            var context = (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", activityThread, stubLoadedApk);
            if (config.appComponentFactory != null) {
                try {
                    appLoadedApk.getClassLoader().loadClass(config.appComponentFactory);
                } catch (Throwable e) {
                    Log.w(TAG, "Original AppComponentFactory not found: " + config.appComponentFactory, e);
                    appInfo.appComponentFactory = null;
                }
            }
            Log.i(TAG, "createLoadedApkWithContext cost: " + (System.currentTimeMillis() - timeStart) + "ms");
            return context;
        } catch (Throwable e) {
            XLog.e(TAG, "createLoadedApk", e);
            return null;
        }
    }

    public static void disableProfile(Context context) {
        var appInfo = context.getApplicationInfo();
        if (appInfo == null) return;

        var codePaths = new ArrayList<String>();
        if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) codePaths.add(appInfo.sourceDir);
        if (appInfo.splitSourceDirs != null) Collections.addAll(codePaths, appInfo.splitSourceDirs);
        if (codePaths.isEmpty()) return;

        var profileDir = HiddenApiBridge.Environment_getDataProfilesDePackageDirectory(appInfo.uid / PER_USER_RANGE, context.getPackageName());

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : appInfo.splitNames[i - 1];
            File profile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof");

            try {
                // 如果已是 0 字節且唯讀，直接跳過
                if (profile.exists() && profile.length() == 0 && !profile.canWrite()) continue;
                // 自動將已存在的檔案內容清空或建立新檔
                try (var ignored = new FileOutputStream(profile)) {
                }
                // 設定檔案只讀
                Os.chmod(profile.getAbsolutePath(), 00444);

            } catch (Throwable e) {
                Log.e(TAG, "Failed to disable profile: " + profile.getName(), e);
            }
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
