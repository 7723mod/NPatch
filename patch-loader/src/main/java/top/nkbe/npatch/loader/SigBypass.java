package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonSyntaxException;

import org.json.JSONException;
import org.json.JSONObject;
import org.lsposed.lspd.nativebridge.SvcBypass;
import top.nkbe.npatch.loader.util.XLog;
import top.nkbe.npatch.share.Constants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SigBypass {

    private static final String TAG = "NPatch-SigBypass";
    private static final Map<String, String> signatures = new HashMap<>();
    private static String cachedOriginalApkPath;
    private static String cachedOriginalFactory = null;
    private static int activeSigBypassLevel;
    private static boolean packageParserHooked;
    private static boolean packageInfoCreatorProxied;
    private static boolean javaIoHooked;
    private static boolean nativeOpenatEnabled;
    private static boolean svcRedirectEnabled;

    private static void replaceSignature(Context context, PackageInfo packageInfo) {
        boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0) || packageInfo.signingInfo != null;
        if (hasSignature) {
            String packageName = packageInfo.packageName;
            String replacement = signatures.get(packageName);
            if (replacement == null && !signatures.containsKey(packageName)) {
                try {
                    var metaData = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
                    String encoded = null;
                    if (metaData != null) encoded = metaData.getString("npatch");
                    if (encoded != null) {
                        var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                        try {
                            var patchConfig = new JSONObject(json);
                            replacement = patchConfig.getString("originalSignature");
                            if (patchConfig.has("appComponentFactory")) {
                                cachedOriginalFactory = patchConfig.optString("appComponentFactory", null);
                            }
                        } catch (JSONException e) {
                            Log.w(TAG, "fail to get originalSignature or factory", e);
                        }
                    }
                } catch (PackageManager.NameNotFoundException | JsonSyntaxException ignored) {
                }
                signatures.put(packageName, replacement);
            }
            if (replacement != null) {
                if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                    XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 1)");
                    packageInfo.signatures[0] = new Signature(replacement);
                }
                if (packageInfo.signingInfo != null) {
                    XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 2)");
                    Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                    if (signaturesArray != null && signaturesArray.length > 0) {
                        signaturesArray[0] = new Signature(replacement);
                    }
                }
            }
        }
    }

    // 移植自 SRPatch
    private static void spoofContextInternalFields(Context context, String fakeApkPath) {
        try {
            Context baseContext = context;
            while (baseContext instanceof android.content.ContextWrapper) {
                baseContext = ((android.content.ContextWrapper) baseContext).getBaseContext();
            }
            java.lang.reflect.Field packageInfoField = baseContext.getClass().getDeclaredField("mPackageInfo");
            packageInfoField.setAccessible(true);
            Object packageInfoObject = packageInfoField.get(baseContext);

            if (packageInfoObject != null) {
                // mAppDir
                java.lang.reflect.Field appDirField = packageInfoObject.getClass().getDeclaredField("mAppDir");
                appDirField.setAccessible(true);
                appDirField.set(packageInfoObject, fakeApkPath);

                // mResDir
                java.lang.reflect.Field resDirField = packageInfoObject.getClass().getDeclaredField("mResDir");
                resDirField.setAccessible(true);
                resDirField.set(packageInfoObject, fakeApkPath);
            }

            // 同步修改當前 Context 的 ApplicationInfo
            ApplicationInfo currentAppInfo = context.getApplicationInfo();
            currentAppInfo.sourceDir = fakeApkPath;
            currentAppInfo.publicSourceDir = fakeApkPath;

        } catch (Throwable t) {
            Log.w(TAG, "Failed to spoof Context internal fields", t);
        }
    }

    private static void spoofApplicationInfo(ApplicationInfo appInfo) {
        if (appInfo != null) {
            if (cachedOriginalFactory != null && !cachedOriginalFactory.isEmpty()) {
                appInfo.appComponentFactory = cachedOriginalFactory;
            }
        }
    }

    private static void replacePackageInfoPath(PackageInfo packageInfo, String fakeApkPath) {
        if (packageInfo != null && packageInfo.applicationInfo != null && fakeApkPath != null) {
            packageInfo.applicationInfo.sourceDir = fakeApkPath;
            packageInfo.applicationInfo.publicSourceDir = fakeApkPath;
        }
    }

    private static void hookPackageParser(Context context) {
        // 同一個目標進程可能重複初始化 loader，hook 只需要安裝一次。
        if (packageParserHooked) return;
        XposedBridge.hookAllMethods(PackageParser.class, "generatePackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var packageInfo = (PackageInfo) param.getResult();
                if (packageInfo == null) return;
                replaceSignature(context, packageInfo);

                if (activeSigBypassLevel >= Constants.SIGBYPASS_LV_PATH_REDIR && cachedOriginalApkPath != null) {
                    replacePackageInfoPath(packageInfo, cachedOriginalApkPath);
                }
            }
        });
        packageParserHooked = true;
    }

    private static void proxyPackageInfoCreator(Context context) {
        // PackageInfo.CREATOR 是全域靜態物件，重複代理會讓 Parcel 邏輯變得不可預期。
        if (packageInfoCreatorProxied) return;
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> proxiedCreator = new Parcelable.Creator<>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                PackageInfo packageInfo = originalCreator.createFromParcel(source);
                replaceSignature(context, packageInfo);

                // 還原 appComponentFactory
                if (packageInfo.applicationInfo != null) {
                    spoofApplicationInfo(packageInfo.applicationInfo);
                }

                if (activeSigBypassLevel >= Constants.SIGBYPASS_LV_PATH_REDIR && cachedOriginalApkPath != null) {
                    replacePackageInfoPath(packageInfo, cachedOriginalApkPath);
                }
                return packageInfo;
            }

            @Override
            public PackageInfo[] newArray(int size) {
                return originalCreator.newArray(size);
            }
        };
        XposedHelpers.setStaticObjectField(PackageInfo.class, "CREATOR", proxiedCreator);
        try {
            Map<?, ?> mCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "mCreators");
            mCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.mCreators", e);
        }
        try {
            Map<?, ?> sPairedCreators = (Map<?, ?>) XposedHelpers.getStaticObjectField(Parcel.class, "sPairedCreators");
            sPairedCreators.clear();
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.sPairedCreators", e);
        }
        packageInfoCreatorProxied = true;
    }

    public static void replaceApplication(String packageName, String sourceDir, String resourcesDir) throws IOException {
        try {
            Log.i(TAG, "Start Replace application info for `" + packageName + "`");
            XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "getApplicationInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (packageName.equals(param.args[0])) {
                        ApplicationInfo info = (ApplicationInfo) param.getResult();
                        info.sourceDir = sourceDir;
                        info.publicSourceDir = sourceDir;
                    }
                }
            });
            XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "getApplicationInfoAsUser", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (packageName.equals(param.args[0])) {
                        ApplicationInfo info = (ApplicationInfo) param.getResult();
                        info.sourceDir = sourceDir;
                        info.publicSourceDir = sourceDir;
                    }
                }
            });
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace getApplicationInfo", e);
        }
    }

    private static boolean isArm64Runtime() {
        // SVC 依賴 ARM64 SIGSYS/ucontext 暫存器佈局，其他 ABI 直接跳過比較穩。
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    private static String extractOriginalApk(Context context) {
        File cacheDir = new File(context.getCacheDir(), "npatch/origin");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            Log.e(TAG, "Failed to create original APK cache directory: " + cacheDir);
            return null;
        }

        try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
            ZipEntry entry = sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH);
            if (entry == null) {
                Log.e(TAG, "Original APK not found in assets!");
                return null;
            }

            File targetFile = new File(cacheDir, entry.getCrc() + ".apk");
            if (targetFile.exists() && targetFile.length() == entry.getSize()) {
                cachedOriginalApkPath = targetFile.getAbsolutePath();
                return cachedOriginalApkPath;
            }

            try (InputStream is = sourceFile.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                }
            }
            cachedOriginalApkPath = targetFile.getAbsolutePath();
            return cachedOriginalApkPath;
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract original APK", e);
            return null;
        }
    }

    private static void hookJavaIO(String currentApkPath, String originalApkPath) {
        // Java IO 先覆蓋常見讀 APK 路徑，native SVC 只補更底層的 openat。
        if (javaIoHooked) return;
        XC_MethodHook redirectHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0) {
                    if (param.args[0] instanceof String) {
                        String path = (String) param.args[0];
                        if (path.equals(currentApkPath)) {
                            param.args[0] = originalApkPath;
                        }
                    } else if (param.args[0] instanceof File) {
                        File file = (File) param.args[0];
                        if (file.getPath().equals(currentApkPath)) {
                            param.args[0] = new File(originalApkPath);
                        }
                    }
                }
            }
        };
        XposedBridge.hookAllConstructors(ZipFile.class, redirectHook);
        try {
            XposedBridge.hookAllConstructors(FileInputStream.class, redirectHook);
        } catch (Throwable ignored) {}
        javaIoHooked = true;
    }

    static void doSigBypass(Context context, int sigBypassLevel) throws IOException {
        // hook 回呼會讀這個等級，確保後續升級等級時既有 hook 也能套用新行為。
        activeSigBypassLevel = Math.max(activeSigBypassLevel, sigBypassLevel);
        String currentApkPath = context.getPackageResourcePath();
        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT && cachedOriginalApkPath == null) {
            cachedOriginalApkPath = extractOriginalApk(context);
        }

        // Java PMS Hook
        if (sigBypassLevel >= 1) {
            hookPackageParser(context);
            proxyPackageInfoCreator(context);
        }

        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT && cachedOriginalApkPath != null) {
            // 1. Java Core IO stability
            hookJavaIO(currentApkPath, cachedOriginalApkPath);
            // 2. Native OpenAt Hook
            if (!nativeOpenatEnabled) {
                org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHook(
                        currentApkPath,
                        cachedOriginalApkPath,
                        context.getPackageName()
                );
                nativeOpenatEnabled = true;
            }

            // 路徑重定向 (Path Redirection)
            if (sigBypassLevel >= 3) {
                try {
                    replaceApplication(context.getPackageName(), cachedOriginalApkPath, cachedOriginalApkPath);

                    spoofContextInternalFields(context, cachedOriginalApkPath);
                    XLog.i(TAG, "Path Redirection (LV3) enabled");
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to apply path redirection", t);
                }
            }

            // SVC (Seccomp) Hook
            if (sigBypassLevel >= Constants.SIGBYPASS_LV_SVC && !svcRedirectEnabled) {
                if (!isArm64Runtime()) {
                    XLog.w(TAG, "SVC Hook skipped on non-arm64 runtime");
                } else if (SvcBypass.initSvcHook()) {
                    SvcBypass.enableSvcRedirect(
                            currentApkPath,
                            cachedOriginalApkPath,
                            context.getPackageName()
                    );
                    svcRedirectEnabled = true;
                    XLog.i(TAG, "SVC Hook enabled");
                } else {
                    XLog.w(TAG, "SVC Hook failed to init");
                }
            }
        } else if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT) {
            XLog.w(TAG, "Original APK unavailable, native signature bypass disabled");
        }
    }
}
