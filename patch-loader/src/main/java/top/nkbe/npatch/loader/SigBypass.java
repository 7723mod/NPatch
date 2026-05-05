package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
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

    private static void hookPackageParser(Context context, int sigBypassLevel) {
        XposedBridge.hookAllMethods(PackageParser.class, "generatePackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var packageInfo = (PackageInfo) param.getResult();
                if (packageInfo == null) return;
                replaceSignature(context, packageInfo);

                if (sigBypassLevel >= 3 && cachedOriginalApkPath != null) {
                    replacePackageInfoPath(packageInfo, cachedOriginalApkPath);
                }
            }
        });
    }

    private static void proxyPackageInfoCreator(Context context, int sigBypassLevel) {
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

                if (sigBypassLevel >= Constants.SIGBYPASS_LV_PATH_REDIR && cachedOriginalApkPath != null) {
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

    private static String extractOriginalApk(Context context) {
        File cacheDir = new File(context.getCacheDir(), "npatch/origin");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
            ZipEntry entry = sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH);
            if (entry == null) {
                Log.e(TAG, "Original APK not found in assets!");
                return null;
            }

            File targetFile = new File(cacheDir, entry.getCrc() + ".apk");
            if (targetFile.exists() && targetFile.length() == entry.getSize()) {
                return targetFile.getAbsolutePath();
            }

            try (InputStream is = sourceFile.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                }
            }
            return targetFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract original APK", e);
            return null;
        }
    }

    private static void hookJavaIO(String currentApkPath, String originalApkPath) {
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
    }

    static void doSigBypass(Context context, int sigBypassLevel) throws IOException {
        String currentApkPath = context.getPackageResourcePath();
        if (sigBypassLevel >= 2) {
            cachedOriginalApkPath = extractOriginalApk(context);
        }

        // Java PMS Hook
        if (sigBypassLevel >= 1) {
            hookPackageParser(context, sigBypassLevel);
            proxyPackageInfoCreator(context, sigBypassLevel);
        }

        if (sigBypassLevel >= 2 && cachedOriginalApkPath != null) {
            // 1. Java Core IO stability
            hookJavaIO(currentApkPath, cachedOriginalApkPath);
            // 2. Native OpenAt Hook
            org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHook(
                    currentApkPath,
                    cachedOriginalApkPath,
                    context.getPackageName()
            );

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
            if (sigBypassLevel >= 4) {
                if (SvcBypass.initSvcHook()) {
                    SvcBypass.enableSvcRedirect(
                            currentApkPath,
                            cachedOriginalApkPath,
                            context.getPackageName()
                    );
                    XLog.i(TAG, "SVC Hook enabled");
                } else {
                    XLog.w(TAG, "SVC Hook failed to init");
                }
            }
        }
    }
}
