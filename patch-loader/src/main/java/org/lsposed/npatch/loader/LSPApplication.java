package org.lsposed.npatch.loader;

import static org.lsposed.npatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.npatch.share.Constants.PROVIDER_DEX_ASSET_PATH;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.os.RemoteException;
import android.os.Process;
import android.system.Os;
import android.util.Log;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.matrix.vector.Startup;
import org.lsposed.npatch.loader.util.XLog;
import org.lsposed.npatch.service.IntegrApplicationService;
import org.lsposed.npatch.service.NeoLocalApplicationService;
import org.lsposed.npatch.service.RemoteApplicationService;
import org.lsposed.npatch.share.Constants;
import org.lsposed.npatch.share.PatchConfig;

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

    private static PatchConfig config;

    private static void logInfo(String msg) {
        XLog.i(TAG, msg);
    }

    private static void logWarn(String msg) {
        XLog.w(TAG, msg);
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

        logInfo("Initialize service client");
        ILSPApplicationService service = null;

        if (config.useManager) {
            try {
                service = new RemoteApplicationService(context);
                List<Module> m = service.getLegacyModulesList();
                JSONArray moduleArr = new JSONArray();
                if (m != null) {
                    for (Module module : m) {
                        JSONObject moduleObj = new JSONObject();
                        moduleObj.put("path", module.apkPath);
                        moduleObj.put("packageName", module.packageName);
                        moduleArr.put(moduleObj);
                    }
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

        disableProfile(context);
        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed(false);

        // WARN: Since it uses `XResource`, the following class should not be initialized
        // before forkPostCommon is invoke. Otherwise, you will get failure of XResources

        if (config.outputLog) {
            XposedBridge.setLogPrinter(new XposedLogPrinter(0, "NPatch"));
        }
        logInfo("Load modules");
        LSPLoader.initModules(appLoadedApk);
        logInfo("Modules initialized");

        switchAllClassLoader();
        SigBypass.doSigBypass(context, config.sigBypassLevel);

        if (config.useMicroG) {
            logInfo("Activating MicroG redirect via NPatch");
            GmsRedirector.activate(context, config.originalSignature);
        }

        logInfo("NPatch bootstrap completed");
    }

    private static Context createLoadedApkWithContext() {
        try {
            var timeStart = System.currentTimeMillis();
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");

            stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");
            var compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(mBoundApplication, "compatInfo");
            var baseClassLoader = stubLoadedApk.getClassLoader();

            try (var is = baseClassLoader.getResourceAsStream(CONFIG_ASSET_PATH)) {
                if (is == null) throw new IOException("Config file not found in assets");
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                config = GSON.fromJson(streamReader, PatchConfig.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            XLog.init(config.newPackage, ActivityThread.currentProcessName(), config.outputLog);
            logInfo("Loaded patch config for " + config.newPackage + ", useManager=" + config.useManager + ", outputLog=" + config.outputLog);
            Log.i(TAG, "Use manager: " + config.useManager);
            Log.i(TAG, "Signature bypass level: " + config.sigBypassLevel);

            if (config.sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT) {
                Path cacheApkPath = OriginApkHelper.prepareOriginApk(appInfo, baseClassLoader);
                appInfo.sourceDir = cacheApkPath.toString();
                appInfo.publicSourceDir = cacheApkPath.toString();
            }
            appInfo.appComponentFactory = config.appComponentFactory;

            Path providerPath = null;
            if (config.injectProvider) {
                Path providerDir = Paths.get(appInfo.dataDir, "cache/npatch/origin/");
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
            appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);

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
                    context.getClassLoader().loadClass(config.appComponentFactory);
                } catch (Throwable e) {
                    Log.w(TAG, "Original AppComponentFactory not found: " + config.appComponentFactory, e);
                    appInfo.appComponentFactory = null;
                }
            }
            Log.i(TAG, "createLoadedApkWithContext cost: " + (System.currentTimeMillis() - timeStart) + "ms");

            SigBypass.replaceApplication(appInfo.packageName, appInfo.sourceDir, appInfo.publicSourceDir);
            return context;
        } catch (Throwable e) {
            Log.e(TAG, "createLoadedApk", e);
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
