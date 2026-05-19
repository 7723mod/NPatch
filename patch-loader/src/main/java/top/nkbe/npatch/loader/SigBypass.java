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
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonSyntaxException;

import org.json.JSONException;
import org.json.JSONObject;
import org.lsposed.lspd.nativebridge.SvcBypass;
import top.nkbe.npatch.loader.util.XLog;
import top.nkbe.npatch.share.Constants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SigBypass {

    private static final String TAG = "NPatch-SigBypass";
    private static final int CERT_INPUT_RAW_X509 = 0;
    private static final int CERT_INPUT_SHA256 = 1;
    private static final Map<String, String> signatures = new HashMap<>();
    private static final Set<String> moduleCallerPrefixes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static String cachedOriginalApkPath;
    private static String cachedPatchedApkPath;
    private static int activeSigBypassLevel;
    private static boolean packageParserHooked;
    private static boolean packageInfoCreatorProxied;
    private static boolean applicationInfoHooked;
    private static boolean packageArchiveInfoHooked;
    private static boolean hasSigningCertificateHooked;
    private static boolean javaIoHooked;
    private static boolean nativeOpenatEnabled;
    private static boolean svcRedirectEnabled;

    static {
        moduleCallerPrefixes.add("top.nkbe.npatch.");
        moduleCallerPrefixes.add("org.matrix.vector.");
        moduleCallerPrefixes.add("de.robv.android.xposed.");
        moduleCallerPrefixes.add("io.github.libxposed.");
        moduleCallerPrefixes.add("org.lsposed.");
    }

    public static void registerModuleCallerPrefix(String prefix) {
        if (prefix != null && !prefix.isEmpty()) {
            moduleCallerPrefixes.add(prefix);
        }
    }

    static boolean isModuleCallerForCompat() {
        return isModuleCaller();
    }

    public static void setPaths(String originalApkPath, String patchedApkPath) {
        cachedOriginalApkPath = originalApkPath;
        cachedPatchedApkPath = patchedApkPath;
    }

    private static boolean isModuleCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            for (String prefix : moduleCallerPrefixes) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSignatureSensitiveCaller() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.startsWith("android.content.pm.PackageParser")
                    || className.startsWith("android.content.pm.parsing.")
                    || className.startsWith("android.util.apk.")
                    || className.startsWith("java.util.jar.")
                    || className.startsWith("sun.security.pkcs.")
                    || className.startsWith("sun.security.util.")
                    || className.startsWith("org.apache.harmony.security.")) {
                return true;
            }
        }
        return false;
    }

    private static String visibleApkPathForCaller() {
        if (isModuleCaller() && cachedPatchedApkPath != null) {
            return cachedPatchedApkPath;
        }
        return cachedOriginalApkPath != null ? cachedOriginalApkPath : cachedPatchedApkPath;
    }

    private static void replaceSignature(Context context, PackageInfo packageInfo) {
        boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0)
                || packageInfo.signingInfo != null;
        if (!hasSignature) return;

        String packageName = packageInfo.packageName;
        String replacement = signatures.get(packageName);
        if (replacement == null && !signatures.containsKey(packageName)) {
            try {
                var metaData = context.getPackageManager()
                        .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                        .metaData;
                String encoded = metaData == null ? null : metaData.getString("npatch");
                if (encoded != null) {
                    var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                    try {
                        var patchConfig = new JSONObject(json);
                        replacement = patchConfig.getString("originalSignature");
                    } catch (JSONException e) {
                        Log.w(TAG, "fail to get originalSignature or factory", e);
                    }
                }
            } catch (PackageManager.NameNotFoundException | JsonSyntaxException ignored) {
            }
            signatures.put(packageName, replacement);
        }

        if (replacement == null) return;

        Signature replacementSignature = new Signature(replacement);
        if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 1)");
            packageInfo.signatures[0] = replacementSignature;
        }
        if (packageInfo.signingInfo != null) {
            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 2)");
            Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
            if (signaturesArray != null && signaturesArray.length > 0) {
                signaturesArray[0] = replacementSignature;
            }
            if (activeSigBypassLevel >= 3) {
                replaceSigningInfoFields(packageInfo.signingInfo, replacementSignature);
            }
        }
    }

    private static void replaceSigningInfoFields(Object signingInfo, Signature replacement) {
        try {
            Object signingDetails = XposedHelpers.getObjectField(signingInfo, "mSigningDetails");
            if (signingDetails == null) return;
            Signature[] replacements = new Signature[]{replacement};
            setFieldIfExists(signingDetails, "signatures", replacements);
            setFieldIfExists(signingDetails, "mSignatures", replacements);
            setFieldIfExists(signingDetails, "pastSigningCertificates", replacements);
            setFieldIfExists(signingDetails, "mPastSigningCertificates", replacements);
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace SigningInfo internals", e);
        }
    }

    private static void setFieldIfExists(Object target, String fieldName, Object value) {
        try {
            XposedHelpers.setObjectField(target, fieldName, value);
        } catch (NoSuchFieldError ignored) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace field " + fieldName, e);
        }
    }

    private static Signature getOriginalSignature(String packageName) {
        String replacement = signatures.get(packageName);
        if (replacement == null || replacement.isEmpty()) return null;
        try {
            return new Signature(replacement);
        } catch (Throwable e) {
            Log.w(TAG, "fail to construct original signature for " + packageName, e);
            return null;
        }
    }

    private static boolean matchesOriginalCertificate(Signature signature, byte[] certificate, int type) {
        if (signature == null || certificate == null) return false;
        try {
            byte[] raw = signature.toByteArray();
            if (type == CERT_INPUT_RAW_X509) {
                return MessageDigest.isEqual(raw, certificate);
            }
            if (type == CERT_INPUT_SHA256) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
                return MessageDigest.isEqual(digest, certificate);
            }
        } catch (Throwable e) {
            Log.w(TAG, "fail to compare signature certificate", e);
        }
        return false;
    }

    private static void hookPackageParser(Context context) {
        if (packageParserHooked) return;
        XposedBridge.hookAllMethods(PackageParser.class, "generatePackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                PackageInfo packageInfo = (PackageInfo) param.getResult();
                if (packageInfo == null) return;
                replaceSignature(context, packageInfo);
            }
        });
        packageParserHooked = true;
    }

    private static void proxyPackageInfoCreator(Context context) {
        if (packageInfoCreatorProxied) return;
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> proxiedCreator = new Parcelable.Creator<>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                PackageInfo packageInfo = originalCreator.createFromParcel(source);
                replaceSignature(context, packageInfo);
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

    private static void replaceApplication(String packageName) {
        if (applicationInfoHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!packageName.equals(param.args[0])) return;
                    ApplicationInfo info = (ApplicationInfo) param.getResult();
                    if (info == null) return;
                }
            };
            XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "getApplicationInfo", hook);
            XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "getApplicationInfoAsUser", hook);
            applicationInfoHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace getApplicationInfo", e);
        }
    }

    private static void hookPackageArchiveInfo(Context context) {
        if (packageArchiveInfoHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (cachedOriginalApkPath == null) return;
                    Object apkPath = param.args.length == 0 ? null : param.args[0];
                    if (!(apkPath instanceof String path) || !path.equals(cachedPatchedApkPath)) {
                        return;
                    }
                    if (isModuleCaller()) {
                        return;
                    }
                    param.args[0] = cachedOriginalApkPath;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    PackageInfo packageInfo = (PackageInfo) param.getResult();
                    if (packageInfo == null) return;
                    replaceSignature(context, packageInfo);
                }
            };
            hookPackageArchiveInfoMethods(PackageManager.class, hook);
            try {
                hookPackageArchiveInfoMethods(Class.forName("android.app.ApplicationPackageManager"), hook);
            } catch (Throwable ignored) {
            }
            packageArchiveInfoHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace getPackageArchiveInfo", e);
        }
    }

    private static void hookHasSigningCertificate(Context context) {
        if (hasSigningCertificateHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isModuleCaller()) return;
                    if (param.args.length < 3) return;
                    Object packageNameArg = param.args[0];
                    Object certificateArg = param.args[1];
                    Object typeArg = param.args[2];
                    if (!(certificateArg instanceof byte[] certificate)
                            || !(typeArg instanceof Integer type)) {
                        return;
                    }
                    String packageName = null;
                    if (packageNameArg instanceof String str) {
                        packageName = str;
                    } else if (packageNameArg instanceof Integer uid && uid == Process.myUid()) {
                        packageName = context.getPackageName();
                    }
                    if (packageName == null) return;
                    Signature originalSignature = getOriginalSignature(packageName);
                    if (originalSignature == null) return;
                    if (matchesOriginalCertificate(originalSignature, certificate, type)) {
                        param.setResult(true);
                    }
                }
            };
            XposedBridge.hookAllMethods(PackageManager.class, "hasSigningCertificate", hook);
            try {
                XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "hasSigningCertificate", hook);
            } catch (Throwable ignored) {
            }
            hasSigningCertificateHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook hasSigningCertificate", e);
        }
    }

    private static void hookPackageArchiveInfoMethods(Class<?> clazz, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(clazz, "getPackageArchiveInfo", hook);
        } catch (NoSuchMethodError ignored) {
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

    private static void hookJavaIO(String patchedApkPath, String originalApkPath) {
        if (javaIoHooked) return;
        XC_MethodHook redirectHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object arg0 = param.args[0];
                boolean isPatchedApkPath = false;
                if (arg0 instanceof String path) {
                    isPatchedApkPath = path.equals(patchedApkPath);
                } else if (arg0 instanceof File file) {
                    isPatchedApkPath = file.getPath().equals(patchedApkPath);
                }
                // Fast-path: most File/Zip opens are unrelated to patched APK, skip stack walking.
                if (!isPatchedApkPath) {
                    return;
                }
                if (!isSignatureSensitiveCaller() || isModuleCaller()) {
                    return;
                }
                if (arg0 instanceof String) {
                    param.args[0] = originalApkPath;
                } else if (arg0 instanceof File) {
                    param.args[0] = new File(originalApkPath);
                }
            }
        };
        XposedBridge.hookAllConstructors(ZipFile.class, redirectHook);
        try {
            XposedBridge.hookAllConstructors(FileInputStream.class, redirectHook);
        } catch (Throwable ignored) {
        }
        javaIoHooked = true;
    }

    static void doSigBypass(Context context, int sigBypassLevel) throws IOException {
        activeSigBypassLevel = Math.max(activeSigBypassLevel, sigBypassLevel);
        String currentApkPath = cachedPatchedApkPath != null ? cachedPatchedApkPath : context.getPackageResourcePath();
        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT && cachedOriginalApkPath == null) {
            cachedOriginalApkPath = extractOriginalApk(context);
        }

        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM) {
            hookPackageParser(context);
            proxyPackageInfoCreator(context);
        }

        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT && cachedOriginalApkPath != null) {
            hookJavaIO(currentApkPath, cachedOriginalApkPath);
            if (!nativeOpenatEnabled) {
                org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHook(
                        currentApkPath,
                        cachedOriginalApkPath,
                        context.getPackageName()
                );
                nativeOpenatEnabled = true;
            }

            if (sigBypassLevel >= 3) {
                hookPackageArchiveInfo(context);
                hookHasSigningCertificate(context);
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
