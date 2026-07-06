package top.nkbe.npatch.loader;

import static top.nkbe.npatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
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
    private static final Map<String, Signature[]> signatureCache = new ConcurrentHashMap<>();
    private static final Set<String> moduleCallerPrefixes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static String redirectApkPath;
    private static String visibleApkPath;
    private static int activeSigBypassLevel;
    private static boolean packageInfoConstructorHooked;
    private static boolean applicationInfoConstructorHooked;
    private static boolean packageArchiveInfoHooked;
    private static boolean hasSigningCertificateHooked;
    private static boolean getPackageInfoHooked;
    private static boolean getApplicationInfoHooked;
    private static boolean apkPathAccessorsHooked;
    private static boolean packageInfoCreatorHooked;
    private static boolean packageParserHooked;
    private static boolean javaIoHooked;
    private static boolean javaFilePathHooked;
    private static boolean nativeOpenatEnabled;
    private static boolean seccompRedirectEnabled;
    private static boolean useMinimalNativeFileHook;
    private static boolean libHideEnabled;

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
            signatureCache.put(packageName, new Signature[]{new Signature(signatureBase64)});
        } catch (Throwable e) {
            Log.w(TAG, "Failed to cache original signature for " + packageName, e);
        }
    }

    public static void setPaths(String originalApkPath, String patchedApkPath) {
        redirectApkPath = originalApkPath;
        visibleApkPath = patchedApkPath;
    }

    private static boolean is360ProtectedApk(String apkPath) {
        if (apkPath == null) return false;
        try (ZipFile apk = new ZipFile(apkPath)) {
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase();
                if (name.contains("qihoo")
                        || name.contains("qihu")
                        || name.contains("360")
                        || name.contains("jiagu")
                        || name.contains("stub_360")) {
                    return true;
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "fail to inspect APK protector", e);
        }
        return false;
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

    private static void setReflectivePathField(ApplicationInfo applicationInfo, String fieldName, String path) {
        try {
            XposedHelpers.setObjectField(applicationInfo, fieldName, path);
        } catch (Throwable ignored) {
        }
    }

    private static boolean matchesTargetApplicationInfo(Context context, ApplicationInfo applicationInfo) {
        if (applicationInfo == null) return false;
        if (redirectApkPath != null) {
            if (redirectApkPath.equals(applicationInfo.sourceDir)
                    || redirectApkPath.equals(applicationInfo.publicSourceDir)) {
                return true;
            }
        }
        if (visibleApkPath != null) {
            if (visibleApkPath.equals(applicationInfo.sourceDir)
                    || visibleApkPath.equals(applicationInfo.publicSourceDir)) {
                return true;
            }
        }
        return context != null && context.getPackageName().equals(applicationInfo.packageName);
    }

    private static void replaceSplitPaths(ApplicationInfo applicationInfo, String fromPath, String toPath) {
        if (applicationInfo == null || fromPath == null || toPath == null) return;
        try {
            Object splitSourceDirs = XposedHelpers.getObjectField(applicationInfo, "splitSourceDirs");
            if (splitSourceDirs instanceof String[] splitPaths) {
                for (int i = 0; i < splitPaths.length; i++) {
                    if (fromPath.equals(splitPaths[i])) {
                        splitPaths[i] = toPath;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            Object splitPublicSourceDirs = XposedHelpers.getObjectField(applicationInfo, "splitPublicSourceDirs");
            if (splitPublicSourceDirs instanceof String[] splitPaths) {
                for (int i = 0; i < splitPaths.length; i++) {
                    if (fromPath.equals(splitPaths[i])) {
                        splitPaths[i] = toPath;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void replaceApplicationInfoPaths(Context context, ApplicationInfo applicationInfo) {
        if (applicationInfo == null || visibleApkPath == null) return;
        if (!matchesTargetApplicationInfo(context, applicationInfo)) return;

        applicationInfo.sourceDir = visibleApkPath;
        applicationInfo.publicSourceDir = visibleApkPath;
        setReflectivePathField(applicationInfo, "scanSourceDir", visibleApkPath);
        setReflectivePathField(applicationInfo, "scanPublicSourceDir", visibleApkPath);
        setReflectivePathField(applicationInfo, "baseCodePath", visibleApkPath);
        setReflectivePathField(applicationInfo, "baseResourcePath", visibleApkPath);
        replaceSplitPaths(applicationInfo, redirectApkPath, visibleApkPath);
    }

    private static void replaceModuleApplicationInfoPaths(Context context, ApplicationInfo applicationInfo) {
        if (applicationInfo == null || redirectApkPath == null) return;
        if (!matchesTargetApplicationInfo(context, applicationInfo)) return;

        applicationInfo.sourceDir = redirectApkPath;
        applicationInfo.publicSourceDir = redirectApkPath;
        setReflectivePathField(applicationInfo, "scanSourceDir", redirectApkPath);
        setReflectivePathField(applicationInfo, "scanPublicSourceDir", redirectApkPath);
        setReflectivePathField(applicationInfo, "baseCodePath", redirectApkPath);
        setReflectivePathField(applicationInfo, "baseResourcePath", redirectApkPath);
        replaceSplitPaths(applicationInfo, visibleApkPath, redirectApkPath);
    }

    private static String mapToVisiblePath(String path) {
        if (path == null || visibleApkPath == null || redirectApkPath == null) return path;
        if (path.equals(redirectApkPath)) return visibleApkPath;
        if (path.equals(redirectApkPath + " (deleted)")) return visibleApkPath + " (deleted)";
        String zipPrefix = redirectApkPath + "!/";
        if (path.startsWith(zipPrefix)) {
            return visibleApkPath + path.substring(redirectApkPath.length());
        }
        return path;
    }

    private static String mapToRedirectPath(String path) {
        if (path == null || visibleApkPath == null || redirectApkPath == null) return path;
        if (path.equals(visibleApkPath)) return redirectApkPath;
        if (path.equals(visibleApkPath + " (deleted)")) return redirectApkPath + " (deleted)";
        String zipPrefix = visibleApkPath + "!/";
        if (path.startsWith(zipPrefix)) {
            return redirectApkPath + path.substring(visibleApkPath.length());
        }
        return path;
    }

    private static boolean shouldSpoofPath(Object receiver, Context context, Object result) {
        if (!(result instanceof String path) || visibleApkPath == null) return false;
        if (path.equals(visibleApkPath)) return false;
        if (redirectApkPath != null && path.equals(redirectApkPath)) return true;

        if (receiver instanceof Context receiverContext) {
            try {
                if (!context.getPackageName().equals(receiverContext.getPackageName())) return false;
                return path.equals(receiverContext.getApplicationInfo().sourceDir)
                        || path.equals(receiverContext.getApplicationInfo().publicSourceDir);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void hookJavaFilePathAccessors() {
        if (javaFilePathHooked) return;

        XC_MethodHook stringPathHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                boolean moduleCaller = isModuleCaller();
                Object result = param.getResult();
                if (!(result instanceof String path)) return;
                String mappedPath = moduleCaller ? mapToRedirectPath(path) : mapToVisiblePath(path);
                if (!path.equals(mappedPath)) {
                    param.setResult(mappedPath);
                }
            }
        };
        XC_MethodHook filePathHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                boolean moduleCaller = isModuleCaller();
                Object result = param.getResult();
                if (!(result instanceof File file)) return;
                String filePath = file.getPath();
                String mappedPath = moduleCaller ? mapToRedirectPath(filePath) : mapToVisiblePath(filePath);
                if (!filePath.equals(mappedPath)) {
                    param.setResult(new File(mappedPath));
                }
            }
        };

        boolean hookedAny = false;
        hookedAny |= hookAllMethodsQuietly(File.class, "getPath", stringPathHook);
        hookedAny |= hookAllMethodsQuietly(File.class, "getAbsolutePath", stringPathHook);
        hookedAny |= hookAllMethodsQuietly(File.class, "getCanonicalPath", stringPathHook);
        hookedAny |= hookAllMethodsQuietly(File.class, "toString", stringPathHook);
        hookedAny |= hookAllMethodsQuietly(File.class, "getAbsoluteFile", filePathHook);
        hookedAny |= hookAllMethodsQuietly(File.class, "getCanonicalFile", filePathHook);

        javaFilePathHooked = hookedAny;
        if (!hookedAny) {
            Log.w(TAG, "fail to hook java.io.File path accessors");
        }
    }

    private static void replaceSigningDetails(Context context, PackageInfo packageInfo) {
        if (packageInfo == null) return;
        boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0)
                || packageInfo.signingInfo != null;
        if (!hasSignature) return;

        String packageName = packageInfo.packageName;
        Signature[] replacements = getOriginalSignatures(context, packageName);

        if (replacements == null || replacements.length == 0) return;

        if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 1)");
            packageInfo.signatures = cloneSignatures(replacements);
        }

        SigningInfo signingInfo = packageInfo.signingInfo;
        if (signingInfo != null) {
            XLog.d(TAG, "Replace signature info for `" + packageName + "` (method 2)");
            try {
                Signature[] signaturesArray = (Signature[]) XposedHelpers.callMethod(signingInfo, "getApkContentsSigners");
                if (signaturesArray != null && signaturesArray.length > 0) {
                    replaceSignatureArray(signaturesArray, replacements);
                }
                Signature[] history = (Signature[]) XposedHelpers.callMethod(signingInfo, "getSigningCertificateHistory");
                if (history != null && history.length > 0) {
                    replaceSignatureArray(history, replacements);
                }
                // Try to replace internal fields if methods don't work or for deeper coverage
                Object mSigningDetails = XposedHelpers.getObjectField(signingInfo, "mSigningDetails");
                if (mSigningDetails != null) {
                    Signature[] pastSignatures = (Signature[]) XposedHelpers.getObjectField(mSigningDetails, "pastSigningCertificates");
                    if (pastSignatures != null && pastSignatures.length > 0) {
                        replaceSignatureArray(pastSignatures, replacements);
                    }
                    Signature[] currentSignatures = (Signature[]) XposedHelpers.getObjectField(mSigningDetails, "signatures");
                    if (currentSignatures != null && currentSignatures.length > 0) {
                        replaceSignatureArray(currentSignatures, replacements);
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "fail to reinforce signingInfo for " + packageName, e);
            }
        }
    }

    private static void replacePackageInfo(Context context, PackageInfo packageInfo, boolean moduleCaller) {
        if (packageInfo == null) return;
        if (moduleCaller) {
            replaceModuleApplicationInfoPaths(context, packageInfo.applicationInfo);
        } else {
            replaceApplicationInfoPaths(context, packageInfo.applicationInfo);
        }
        replaceSigningDetails(context, packageInfo);
    }

    public static ApplicationInfo createModuleCompatibleApplicationInfo(ApplicationInfo applicationInfo) {
        if (applicationInfo == null) return null;
        ApplicationInfo copy = new ApplicationInfo(applicationInfo);
        replaceApplicationInfoPaths(null, copy);
        return copy;
    }

    private static void clearMapFieldQuietly(Class<?> clazz, String fieldName) {
        try {
            Object map = XposedHelpers.getStaticObjectField(clazz, fieldName);
            if (map instanceof Map<?, ?> m) {
                m.clear();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void clearPackageInfoCreatorCaches() {
        try {
            Object cache = XposedHelpers.getStaticObjectField(PackageManager.class, "sPackageInfoCache");
            XposedHelpers.callMethod(cache, "clear");
        } catch (Throwable ignored) {
        }
        clearMapFieldQuietly(Parcel.class, "mCreators");
        clearMapFieldQuietly(Parcel.class, "sPairedCreators");
    }

    private static Signature[] getOriginalSignatures(Context context, String packageName) {
        if (packageName == null) return null;
        Signature[] cached = signatureCache.get(packageName);
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
                Signature[] signatures = new Signature[]{new Signature(replacementStr)};
                signatureCache.put(packageName, signatures);
                return signatures;
            } catch (Throwable e) {
                Log.w(TAG, "fail to construct original signature for " + packageName, e);
            }
        }
        return null;
    }

    private static Signature[] cloneSignatures(Signature[] signatures) {
        if (signatures == null) return null;
        Signature[] cloned = new Signature[signatures.length];
        for (int i = 0; i < signatures.length; i++) {
            cloned[i] = signatures[i] == null ? null : new Signature(signatures[i].toByteArray());
        }
        return cloned;
    }

    private static void replaceSignatureArray(Signature[] target, Signature[] replacements) {
        if (target == null || replacements == null) return;
        int count = Math.min(target.length, replacements.length);
        for (int i = 0; i < count; i++) {
            target[i] = replacements[i] == null ? null : new Signature(replacements[i].toByteArray());
        }
    }

    private static boolean matchesOriginalCertificate(Signature[] originals, byte[] certificate, int type) {
        if (originals == null || certificate == null) return false;
        try {
            for (Signature original : originals) {
                if (original == null) continue;
                byte[] raw = original.toByteArray();
                if (type == CERT_INPUT_RAW_X509 && MessageDigest.isEqual(raw, certificate)) {
                    return true;
                }
                if (type == CERT_INPUT_SHA256) {
                    byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
                    if (MessageDigest.isEqual(digest, certificate)) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "fail to compare signature certificate", e);
        }
        return false;
    }

    private static boolean hookPackageInfoConstructor(Context context) {
        if (packageInfoConstructorHooked) return true;
        try {
            XposedHelpers.findAndHookConstructor(PackageInfo.class, Parcel.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    replacePackageInfo(context, (PackageInfo) param.thisObject, isModuleCaller());
                }
            });
            packageInfoConstructorHooked = true;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook PackageInfo(Parcel); IPC signature replacement disabled", e);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void hookPackageInfoCreator(Context context) {
        if (packageInfoCreatorHooked) return;
        try {
            Parcelable.Creator<PackageInfo> originalCreator =
                    (Parcelable.Creator<PackageInfo>) XposedHelpers.getStaticObjectField(PackageInfo.class, "CREATOR");
            Parcelable.Creator<PackageInfo> wrapper = new Parcelable.Creator<>() {
                @Override
                public PackageInfo createFromParcel(Parcel source) {
                    PackageInfo packageInfo = originalCreator.createFromParcel(source);
                    replacePackageInfo(context, packageInfo, isModuleCaller());
                    return packageInfo;
                }

                @Override
                public PackageInfo[] newArray(int size) {
                    return originalCreator.newArray(size);
                }
            };
            XposedHelpers.setStaticObjectField(PackageInfo.class, "CREATOR", wrapper);
            clearPackageInfoCreatorCaches();
            packageInfoCreatorHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to replace PackageInfo.CREATOR", e);
        }
    }

    private static void hookPackageParserGeneratePackageInfo(Context context) {
        if (packageParserHooked) return;
        try {
            Class<?> packageParser = Class.forName("android.content.pm.PackageParser");
            XposedBridge.hookAllMethods(packageParser, "generatePackageInfo", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result instanceof PackageInfo packageInfo) {
                        replacePackageInfo(context, packageInfo, isModuleCaller());
                    }
                }
            });
            packageParserHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook PackageParser.generatePackageInfo", e);
        }
    }

    private static void hookApplicationInfoConstructor(Context context) {
        if (applicationInfoConstructorHooked) return;
        try {
            XposedHelpers.findAndHookConstructor(ApplicationInfo.class, Parcel.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isModuleCaller()) {
                        replaceModuleApplicationInfoPaths(context, (ApplicationInfo) param.thisObject);
                    } else {
                        replaceApplicationInfoPaths(context, (ApplicationInfo) param.thisObject);
                    }
                }
            });
            applicationInfoConstructorHooked = true;
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook ApplicationInfo(Parcel); path spoof disabled", e);
        }
    }

    private static void hookGetPackageInfo(Context context) {
        if (getPackageInfoHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    replacePackageInfo(context, (PackageInfo) param.getResult(), isModuleCaller());
                }
            };
            boolean hookedAny = false;
            try {
                XposedBridge.hookAllMethods(PackageManager.class, "getPackageInfo", hook);
                hookedAny = true;
            } catch (Throwable ignored) {}
            try {
                Class<?> appPm = Class.forName("android.app.ApplicationPackageManager");
                XposedBridge.hookAllMethods(appPm, "getPackageInfo", hook);
                XposedBridge.hookAllMethods(appPm, "getPackageInfoAsUser", hook);
                hookedAny = true;
            } catch (Throwable ignored) {}
            getPackageInfoHooked = hookedAny;
            if (!hookedAny) {
                Log.w(TAG, "fail to hook concrete getPackageInfo methods");
            }
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook getPackageInfo", e);
        }
    }

    private static void hookGetApplicationInfo(Context context) {
        if (getApplicationInfoHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (isModuleCaller()) {
                        replaceModuleApplicationInfoPaths(context, (ApplicationInfo) param.getResult());
                    } else {
                        replaceApplicationInfoPaths(context, (ApplicationInfo) param.getResult());
                    }
                }
            };
            boolean hookedAny = false;
            try {
                Class<?> appPm = Class.forName("android.app.ApplicationPackageManager");
                XposedBridge.hookAllMethods(appPm, "getApplicationInfo", hook);
                XposedBridge.hookAllMethods(appPm, "getApplicationInfoAsUser", hook);
                hookedAny = true;
            } catch (Throwable ignored) {}
            getApplicationInfoHooked = hookedAny;
            if (!hookedAny) {
                Log.w(TAG, "fail to hook concrete getApplicationInfo methods");
            }
        } catch (Throwable e) {
            Log.w(TAG, "fail to hook getApplicationInfo", e);
        }
    }

    private static void hookApkPathAccessors(Context context) {
        if (apkPathAccessorsHooked) return;

        XC_MethodHook pathHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                if (!(result instanceof String path)) return;
                if (isModuleCaller()) {
                    String redirectPath = mapToRedirectPath(path);
                    if (!path.equals(redirectPath)) {
                        param.setResult(redirectPath);
                    }
                    return;
                }
                if (shouldSpoofPath(param.thisObject, context, param.getResult())) {
                    param.setResult(visibleApkPath);
                }
            }
        };

        boolean hookedAny = false;
        hookedAny |= hookAllMethodsQuietly(Context.class, "getPackageCodePath", pathHook);
        hookedAny |= hookAllMethodsQuietly(Context.class, "getPackageResourcePath", pathHook);
        hookedAny |= hookAllMethodsQuietly("android.content.ContextWrapper", "getPackageCodePath", pathHook);
        hookedAny |= hookAllMethodsQuietly("android.content.ContextWrapper", "getPackageResourcePath", pathHook);
        hookedAny |= hookAllMethodsQuietly("android.app.ContextImpl", "getPackageCodePath", pathHook);
        hookedAny |= hookAllMethodsQuietly("android.app.ContextImpl", "getPackageResourcePath", pathHook);
        hookedAny |= hookAllMethodsQuietly("android.app.LoadedApk", "getResDir", pathHook);

        apkPathAccessorsHooked = hookedAny;
        if (!hookedAny) {
            Log.w(TAG, "fail to hook APK path accessors");
        }
    }

    private static boolean hookAllMethodsQuietly(Class<?> clazz, String methodName, XC_MethodHook hook) {
        try {
            XposedBridge.hookAllMethods(clazz, methodName, hook);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hookAllMethodsQuietly(String className, String methodName, XC_MethodHook hook) {
        try {
            return hookAllMethodsQuietly(Class.forName(className), methodName, hook);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void hookPackageArchiveInfo(Context context) {
        if (packageArchiveInfoHooked) return;
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (visibleApkPath == null || redirectApkPath == null) return;
                    Object apkPath = param.args.length == 0 ? null : param.args[0];
                    if (!(apkPath instanceof String path) || !path.equals(visibleApkPath)) {
                        return;
                    }
                    param.args[0] = redirectApkPath;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    replacePackageInfo(context, (PackageInfo) param.getResult(), isModuleCaller());
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
                    Signature[] originals = getOriginalSignatures(context, packageName);
                    if (originals == null) return;
                    if (matchesOriginalCertificate(originals, certificate, type)) {
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
                redirectApkPath = targetFile.getAbsolutePath();
                return redirectApkPath;
            }

            try (InputStream is = sourceFile.getInputStream(entry);
                 FileOutputStream fos = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, length);
                }
            }
            redirectApkPath = targetFile.getAbsolutePath();
            return redirectApkPath;
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

    private static int effectiveHookLevel(int sigBypassLevel) {
        if (sigBypassLevel >= Constants.SIGBYPASS_SECCOMP) {
            return Constants.SIGBYPASS_EXTREME;
        }
        return sigBypassLevel;
    }

    static void doSigBypass(Context context, int sigBypassLevel, boolean hideLibs) throws IOException {
        activeSigBypassLevel = Math.max(activeSigBypassLevel, sigBypassLevel);
        int hookLevel = effectiveHookLevel(sigBypassLevel);
        String currentApkPath = visibleApkPath != null ? visibleApkPath : context.getPackageResourcePath();

        hideLibs = hideLibs && hookLevel >= Constants.SIGBYPASS_BASIC;

        if (hookLevel >= Constants.SIGBYPASS_BASIC && redirectApkPath == null) {
            redirectApkPath = extractOriginalApk(context);
        }

        if (hookLevel >= Constants.SIGBYPASS_BASIC && redirectApkPath != null) {
            hookJavaIO(currentApkPath, redirectApkPath);
            hookJavaFilePathAccessors();
            useMinimalNativeFileHook = useMinimalNativeFileHook
                    || (hookLevel >= Constants.SIGBYPASS_EXTREME
                    && is360ProtectedApk(redirectApkPath));
            if (useMinimalNativeFileHook) {
                XLog.i(TAG, "360-like protector detected, using minimal native APK redirect");
                org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHookMinimal(
                        currentApkPath,
                        redirectApkPath,
                        context.getPackageName(),
                        hideLibs
                );
            } else {
                org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHook(
                        currentApkPath,
                        redirectApkPath,
                        context.getPackageName(),
                        hideLibs
                );
            }
            nativeOpenatEnabled = true;
            libHideEnabled = hideLibs;
        }

        if (hookLevel >= Constants.SIGBYPASS_HIGH) {
            hookPackageArchiveInfo(context);
            hookHasSigningCertificate(context);
            hookGetApplicationInfo(context);
            hookApkPathAccessors(context);
        }

        if (hookLevel >= Constants.SIGBYPASS_EXTREME) {
            boolean parcelHooked = hookPackageInfoConstructor(context);
            if (!parcelHooked) {
                hookPackageInfoCreator(context);
            }
            hookPackageParserGeneratePackageInfo(context);
            hookApplicationInfoConstructor(context);
            hookGetPackageInfo(context);
        }

        boolean useSeccompRedirect = redirectApkPath != null
                && sigBypassLevel == Constants.SIGBYPASS_SECCOMP;
        if (useSeccompRedirect) {
            if (!isSeccompRuntimeSupported()) {
                XLog.w(TAG, "Seccomp skipped on non-arm64 runtime ABI");
            } else if (FunPatch.enableSeccompV2Redirect(
                        currentApkPath,
                        redirectApkPath,
                        context.getPackageName()
                )) {
                if (!seccompRedirectEnabled) XLog.i(TAG, "Seccomp enabled");
                seccompRedirectEnabled = true;
            } else {
                XLog.w(TAG, "Seccomp failed to init");
            }
        } else if (hookLevel >= Constants.SIGBYPASS_BASIC && redirectApkPath == null) {
            XLog.w(TAG, "Original APK unavailable, native signature bypass disabled");
        }
    }
}
