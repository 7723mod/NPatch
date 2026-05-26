package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Process;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonSyntaxException;

import org.json.JSONException;
import org.json.JSONObject;
import org.lsposed.lspd.nativebridge.FunPatch;
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
    private static final Map<String, Signature> signatureCache = new ConcurrentHashMap<>();
    private static final Set<String> moduleCallerPrefixes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static String cachedOriginalApkPath;
    private static String cachedPatchedApkPath;
    private static int activeSigBypassLevel;
    private static boolean packageInfoConstructorHooked;
    private static boolean packageArchiveInfoHooked;
    private static boolean hasSigningCertificateHooked;
    private static boolean getPackageInfoHooked;
    private static boolean javaIoHooked;
    private static boolean nativeOpenatEnabled;
    private static boolean seccompRedirectEnabled;

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

    public static boolean isModuleCallerForCompat() {
        return isModuleCaller();
    }

    public static void setOriginalSignature(String packageName, String signatureBase64) {
        if (packageName == null || signatureBase64 == null) return;
        try {
            signatureCache.put(packageName, new Signature(signatureBase64));
        } catch (Throwable e) {
            Log.w(TAG, "Failed to cache original signature for " + packageName, e);
        }
    }

    public static void setPaths(String originalApkPath, String patchedApkPath) {
        cachedOriginalApkPath = originalApkPath;
        cachedPatchedApkPath = patchedApkPath;
    }

    private record CallerContext(boolean isModule, boolean isSensitive) {}

    private static CallerContext checkCallerContext() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        boolean isModule = false;
        boolean isSensitive = false;
        // Limit depth to 20 for performance
        int depth = Math.min(stack.length, 25);
        for (int i = 2; i < depth; i++) {
            String className = stack[i].getClassName();
            if (!isModule) {
                for (String prefix : moduleCallerPrefixes) {
                    if (className.startsWith(prefix)) {
                        isModule = true;
                        break;
                    }
                }
            }
            if (!isSensitive) {
                if (className.startsWith("android.content.pm.PackageParser")
                        || className.startsWith("android.content.pm.parsing.")
                        || className.startsWith("android.util.apk.")
                        || className.startsWith("java.util.jar.")
                        || className.startsWith("sun.security.pkcs.")
                        || className.startsWith("sun.security.util.")
                        || className.startsWith("org.apache.harmony.security.")) {
                    isSensitive = true;
                }
            }
            if (isModule && isSensitive) break;
        }
        return new CallerContext(isModule, isSensitive);
    }

    private static boolean isModuleCaller() {
        return checkCallerContext().isModule;
    }

    private static void replaceSignature(Context context, PackageInfo packageInfo) {
        if (packageInfo == null) return;
        boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0)
                || packageInfo.signingInfo != null;
        if (!hasSignature) return;

        String packageName = packageInfo.packageName;
        Signature replacement = getOriginalSignature(context, packageName);

        if (replacement == null) return;

        if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 1)");
            packageInfo.signatures[0] = replacement;
        }

        SigningInfo signingInfo = packageInfo.signingInfo;
        if (signingInfo != null) {
            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 2)");
            try {
                Signature[] signaturesArray = (Signature[]) XposedHelpers.callMethod(signingInfo, "getApkContentsSigners");
                if (signaturesArray != null && signaturesArray.length > 0) {
                    signaturesArray[0] = replacement;
                }
                Signature[] history = (Signature[]) XposedHelpers.callMethod(signingInfo, "getSigningCertificateHistory");
                if (history != null && history.length > 0) {
                    history[0] = replacement;
                }
                // Try to replace internal fields if methods don't work or for deeper coverage
                Object mSigningDetails = XposedHelpers.getObjectField(signingInfo, "mSigningDetails");
                if (mSigningDetails != null) {
                    Signature[] pastSignatures = (Signature[]) XposedHelpers.getObjectField(mSigningDetails, "pastSigningCertificates");
                    if (pastSignatures != null && pastSignatures.length > 0) {
                        pastSignatures[0] = replacement;
                    }
                    Signature[] currentSignatures = (Signature[]) XposedHelpers.getObjectField(mSigningDetails, "signatures");
                    if (currentSignatures != null && currentSignatures.length > 0) {
                        currentSignatures[0] = replacement;
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "fail to reinforce signingInfo for " + packageName, e);
            }
        }
    }

    private static Signature getOriginalSignature(Context context, String packageName) {
        if (packageName == null) return null;
        Signature cached = signatureCache.get(packageName);
        if (cached != null) return cached;

        String replacementStr = null;
        try {
            var metaData = context.getPackageManager()
                    .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
                    .metaData;
            String encoded = metaData == null ? null : metaData.getString("npatch");
            if (encoded != null) {
                var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                try {
                    var patchConfig = new JSONObject(json);
                    replacementStr = patchConfig.getString("originalSignature");
                } catch (JSONException e) {
                    Log.w(TAG, "fail to get originalSignature from metadata", e);
                }
            }
        } catch (PackageManager.NameNotFoundException | JsonSyntaxException ignored) {
        }

        if (replacementStr != null) {
            try {
                Signature sig = new Signature(replacementStr);
                signatureCache.put(packageName, sig);
                return sig;
            } catch (Throwable e) {
                Log.w(TAG, "fail to construct original signature for " + packageName, e);
            }
        }
        return null;
    }

    private static boolean matchesOriginalCertificate(Signature original, byte[] certificate, int type) {
        if (original == null || certificate == null) return false;
        try {
            byte[] raw = original.toByteArray();
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

    private static void hookPackageInfoConstructor(Context context) {
        if (packageInfoConstructorHooked) return;
        try {
            XposedHelpers.findAndHookConstructor(PackageInfo.class, Parcel.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    replaceSignature(context, (PackageInfo) param.thisObject);
                }
            });
            packageInfoConstructorHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook PackageInfo(Parcel); IPC signature replacement disabled", e);
        }
    }

    private static void hookGetPackageInfo(Context context) {
        if (getPackageInfoHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isModuleCaller()) return;
                    replaceSignature(context, (PackageInfo) param.getResult());
                }
            };
            XposedBridge.hookAllMethods(PackageManager.class, "getPackageInfo", hook);
            XposedBridge.hookAllMethods(PackageManager.class, "getPackageInfoAsUser", hook);
            try {
                Class<?> appPm = Class.forName("android.app.ApplicationPackageManager");
                XposedBridge.hookAllMethods(appPm, "getPackageInfo", hook);
                XposedBridge.hookAllMethods(appPm, "getPackageInfoAsUser", hook);
            } catch (Throwable ignored) {}
            getPackageInfoHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook getPackageInfo", e);
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
                    if (isModuleCaller()) return;
                    param.args[0] = cachedOriginalApkPath;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    replaceSignature(context, (PackageInfo) param.getResult());
                }
            };
            XposedBridge.hookAllMethods(PackageManager.class, "getPackageArchiveInfo", hook);
            try {
                XposedBridge.hookAllMethods(Class.forName("android.app.ApplicationPackageManager"), "getPackageArchiveInfo", hook);
            } catch (Throwable ignored) {}
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
                    Signature original = getOriginalSignature(context, packageName);
                    if (original == null) return;
                    if (matchesOriginalCertificate(original, certificate, type)) {
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

    private static boolean isSeccompRuntimeSupported() {
        String[] runtimeAbis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        for (String abi : runtimeAbis) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    private static String extractOriginalApk(Context context) {
        File cacheDir = new File(context.getCacheDir(), "code_cache");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) return null;

        try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
            ZipEntry entry = sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH);
            if (entry == null) return null;

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
                if (!isPatchedApkPath) return;

                CallerContext ctx = checkCallerContext();
                if (ctx.isModule || !ctx.isSensitive) return;

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
        } catch (Throwable ignored) {}
        javaIoHooked = true;
    }

    static void doSigBypass(Context context, int sigBypassLevel) throws IOException {
        activeSigBypassLevel = Math.max(activeSigBypassLevel, sigBypassLevel);
        String currentApkPath = cachedPatchedApkPath != null ? cachedPatchedApkPath : context.getPackageResourcePath();
        if (sigBypassLevel >= Constants.SIGBYPASS_BASIC && cachedOriginalApkPath == null) {
            cachedOriginalApkPath = extractOriginalApk(context);
        }

        if (sigBypassLevel >= Constants.SIGBYPASS_BASIC && cachedOriginalApkPath != null) {
            hookJavaIO(currentApkPath, cachedOriginalApkPath);
            org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHook(
                    currentApkPath,
                    cachedOriginalApkPath,
                    context.getPackageName()
            );
            nativeOpenatEnabled = true;
        }

        if (sigBypassLevel >= Constants.SIGBYPASS_HIGH) {
            hookPackageArchiveInfo(context);
            hookHasSigningCertificate(context);
        }

        if (sigBypassLevel >= Constants.SIGBYPASS_EXTREME) {
            hookPackageInfoConstructor(context);
            hookGetPackageInfo(context);
        }

        if (sigBypassLevel == Constants.SIGBYPASS_SECCOMP && cachedOriginalApkPath != null) {
            if (!isSeccompRuntimeSupported()) {
                XLog.w(TAG, "Seccomp skipped on non-arm64 runtime ABI");
            } else if (FunPatch.enableSeccompV2Redirect(
                        currentApkPath,
                        cachedOriginalApkPath,
                        context.getPackageName()
                )) {
                if (!seccompRedirectEnabled) XLog.i(TAG, "Seccomp enabled");
                seccompRedirectEnabled = true;
            } else {
                XLog.w(TAG, "Seccomp failed to init");
            }
        } else if (sigBypassLevel >= Constants.SIGBYPASS_BASIC && cachedOriginalApkPath == null) {
            XLog.w(TAG, "Original APK unavailable, native signature bypass disabled");
        }
    }
}
