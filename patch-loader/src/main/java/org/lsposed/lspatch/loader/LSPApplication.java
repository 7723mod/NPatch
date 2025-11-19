package org.lsposed.lspatch.loader;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.PROVIDER_DEX_ASSET_PATH;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;

import com.google.gson.Gson;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.lspatch.loader.util.FileUtils;
import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.service.IntegrApplicationService;
import org.lsposed.lspatch.service.NeoLocalApplicationService;
import org.lsposed.lspatch.service.RemoteApplicationService;
import org.lsposed.lspatch.share.PatchConfig;
import org.lsposed.lspd.core.Startup;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

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
import java.util.zip.ZipFile;

import dalvik.system.DexFile;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import hidden.HiddenApiBridge;

/**
 * Created by Windysha
 */
@SuppressWarnings("unused")
public class LSPApplication {

    private static final String TAG = "NPatch";
    private static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    private static final int PER_USER_RANGE = 100000;

    private static ActivityThread activityThread;
    private static LoadedApk stubLoadedApk;
    private static LoadedApk appLoadedApk;

    private static PatchConfig config;

    public static boolean isIsolated() {
        return (android.os.Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    private static boolean hasEmbeddedModules(Context context) {
        try {
            String[] list = context.getAssets().list("lspatch/modules");
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

        Log.d(TAG, "Initialize service client");
        ILSPApplicationService service = null;

        if (config.useManager) {
            try {
                service = new RemoteApplicationService(context);
                List<Module> m = service.getLegacyModulesList();
                JSONArray moduleArr = new JSONArray();
                for (Module module : m) {
                    JSONObject moduleObj = new JSONObject();
                    moduleObj.put("path", module.apkPath);
                    moduleObj.put("packageName", module.packageName);
                    moduleArr.put(moduleObj);
                }
                SharedPreferences shared = context.getSharedPreferences("npatch", Context.MODE_PRIVATE);
                shared.edit().putString("modules", moduleArr.toString()).apply();
                Log.i(TAG, "Success update module scope from Manager");

            } catch (Throwable e) {
                Log.w(TAG, "Failed to connect to manager: " + e.getMessage());
                service = null;
            }
        }

        if (service == null) {

            if (hasEmbeddedModules(context)) {
                Log.i(TAG, "Using Integrated Service (Embedded Modules Found)");
                service = new IntegrApplicationService(context);
            } else {
                Log.i(TAG, "Using NeoLocal Service (Cached Config)");
                service = new NeoLocalApplicationService(context);
            }
        }


        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed();
        // WARN: Since it uses `XResource`, the following class should not be initialized
        // before forkPostCommon is invoke. Otherwise, you will get failure of XResources

        if (config.outputLog){
            XposedBridge.setLogPrinter(new XposedLogPrinter(0,"NPatch"));
        }
        Log.i(TAG, "Load modules");
        LSPLoader.initModules(appLoadedApk);
        Log.i(TAG, "Modules initialized");

        switchAllClassLoader();
        SigBypass.doSigBypass(context, config.sigBypassLevel);

        Log.i(TAG, "NPatch bootstrap completed");
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
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                config = new Gson().fromJson(streamReader, PatchConfig.class);
            } catch (IOException e) {
                Log.e(TAG, "Failed to load config file", e);
                return null;
            }
            Log.i(TAG, "Use manager: " + config.useManager);
            Log.i(TAG, "Signature bypass level: " + config.sigBypassLevel);

            Path originPath = Paths.get(appInfo.dataDir, "cache/npatch/origin/");
            Path cacheApkPath;
            try (ZipFile sourceFile = new ZipFile(appInfo.sourceDir)) {
                cacheApkPath = originPath.resolve(sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH).getCrc() + ".apk");
            }
            String sourceFileaa = appInfo.sourceDir;

            appInfo.sourceDir = cacheApkPath.toString();
            appInfo.publicSourceDir = cacheApkPath.toString();
            appInfo.appComponentFactory = config.appComponentFactory;

            if (!Files.exists(cacheApkPath)) {
                Log.i(TAG, "Extract original apk");
                FileUtils.deleteFolderIfExists(originPath);
                Files.createDirectories(originPath);
                try (InputStream is = baseClassLoader.getResourceAsStream(ORIGINAL_APK_ASSET_PATH)) {
                    Files.copy(is, cacheApkPath);
                }
            }
            Path providerPath = null;
            if (config.injectProvider){
                try (ZipFile sourceFile = new ZipFile(sourceFileaa)) {

                    providerPath = Paths.get(appInfo.dataDir, "cache/npatch/origin/p_" + sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH).getCrc()+".dex");
                    Files.deleteIfExists(providerPath);
                    try (InputStream is = baseClassLoader.getResourceAsStream(PROVIDER_DEX_ASSET_PATH)) {
                        Files.copy(is, providerPath);
                    }
                    providerPath.toFile().setWritable(false);
                }catch (Exception e){
                    Log.e(TAG, "Failed to inject provider:" + Log.getStackTraceString(e));
                }

            }

            cacheApkPath.toFile().setWritable(false);

            var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mPackages");
            mPackages.remove(appInfo.packageName);
            appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);


            if (config.injectProvider){
                ClassLoader loader = appLoadedApk.getClassLoader();
                Object dexPathList = XposedHelpers.getObjectField(loader, "pathList");
                Object dexElements = XposedHelpers.getObjectField(dexPathList, "dexElements");
                int length = Array.getLength(dexElements);
                Object newElements = Array.newInstance(dexElements.getClass().getComponentType(), length + 1);
                System.arraycopy(dexElements, 0, newElements, 0, length);

                DexFile dexFile = new DexFile(providerPath.toString());
                Object element = XposedHelpers.newInstance(XposedHelpers.findClass("dalvik.system.DexPathList$Element",loader), new Class[]{
                        DexFile.class
                },dexFile);
                Array.set(newElements, length, element);
                XposedHelpers.setObjectField(dexPathList, "dexElements", newElements);
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
            Log.i(TAG,"createLoadedApkWithContext cost: " + (System.currentTimeMillis() - timeStart) + "ms");

            SigBypass.replaceApplication(appInfo.packageName, appInfo.sourceDir, appInfo.publicSourceDir);
            return context;
        } catch (Throwable e) {
            Log.e(TAG, "createLoadedApk", e);
            return null;
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
