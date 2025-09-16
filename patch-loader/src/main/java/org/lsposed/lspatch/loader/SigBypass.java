package org.lsposed.lspatch.loader;

import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonSyntaxException;

import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.share.Constants;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class SigBypass {

    private static final String TAG = "LSPatch-SigBypass";
    private static final Map<String, String> signatures = new HashMap<>();

    //從應用程式的 metadata 中獲取原始簽名。
    private static String getOriginalSignatureFromMetadata(Context context, String packageName) {
        try {
            var metaData = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA).metaData;
            if (metaData == null) {
                XLog.d(TAG, "No metadata found for package: " + packageName);
                return null;
            }
            String encoded = metaData.getString("lspatch");
            if (encoded == null) {
                XLog.d(TAG, "No 'lspatch' metadata found for package: " + packageName);
                return null;
            }
            var json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            var patchConfig = new JSONObject(json);
            return patchConfig.getString("originalSignature");
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Application package not found: " + packageName, e);
        } catch (JsonSyntaxException | JSONException e) {
            Log.e(TAG, "Failed to parse signature from metadata for " + packageName, e);
        }
        return null;
    }

    // 更新 PackageInfo 物件中的簽名。
    private static void replaceSignature(Context context, PackageInfo packageInfo) {
        if (packageInfo == null) {
            return;
        }

        boolean hasSignature = (packageInfo.signatures != null && packageInfo.signatures.length != 0) || packageInfo.signingInfo != null;
        if (!hasSignature) {
            return;
        }

        String packageName = packageInfo.packageName;
        String replacement = signatures.get(packageName);

        // 如果簽名不在緩存中，則從 metadata 中獲取並緩存
        if (replacement == null && !signatures.containsKey(packageName)) {
            replacement = getOriginalSignatureFromMetadata(context, packageName);
            signatures.put(packageName, replacement);
        }

        if (replacement != null) {
            Signature newSignature = new Signature(replacement);
            if (packageInfo.signatures != null && packageInfo.signatures.length > 0) {
                XLog.d(TAG, "Replacing signatures array for `" + packageName + "`");
                Arrays.fill(packageInfo.signatures, newSignature);
            }
            // 替換 signingInfo 中的簽名
            if (packageInfo.signingInfo != null) {
                XLog.d(TAG, "Replacing signatures in signingInfo for `" + packageName + "`");
                Signature[] signaturesArray = packageInfo.signingInfo.getApkContentsSigners();
                if (signaturesArray != null && signaturesArray.length > 0) {
                    Arrays.fill(signaturesArray, newSignature);
                }
            }
        } else {
            XLog.w(TAG, "Original signature not found, skipping replacement for: " + packageName);
        }
    }

    private static void hookPackageParser(Context context) {
        XposedBridge.hookAllMethods(PackageParser.class, "generatePackageInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                var packageInfo = (PackageInfo) param.getResult();
                replaceSignature(context, packageInfo);
            }
        });
    }

    private static void proxyPackageInfoCreator(Context context) {
        Parcelable.Creator<PackageInfo> originalCreator = PackageInfo.CREATOR;
        Parcelable.Creator<PackageInfo> proxiedCreator = new Parcelable.Creator<>() {
            @Override
            public PackageInfo createFromParcel(Parcel source) {
                PackageInfo packageInfo = originalCreator.createFromParcel(source);
                if (packageInfo != null) {
                    replaceSignature(context, packageInfo);
                }
                return packageInfo;
            }

            @Override
            public PackageInfo[] newArray(int size) {
                return originalCreator.newArray(size);
            }
        };
        XposedHelpers.setStaticObjectField(PackageInfo.class, "CREATOR", proxiedCreator);

        // 清除 Parcel 的緩存，確保 CREATOR 的替換生效
        try {
            XposedHelpers.getStaticObjectField(Parcel.class, "mCreators");
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.mCreators", e);
        }
        try {
            XposedHelpers.getStaticObjectField(Parcel.class, "sPairedCreators");
        } catch (NoSuchFieldError ignore) {
        } catch (Throwable e) {
            Log.w(TAG, "fail to clear Parcel.sPairedCreators", e);
        }
    }

    static void doSigBypass(Context context, int sigBypassLevel) throws IOException {
        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM) {
            hookPackageParser(context);
            proxyPackageInfoCreator(context);
        }
        if (sigBypassLevel >= Constants.SIGBYPASS_LV_PM_OPENAT) {
            String cacheApkPath;
            try (ZipFile sourceFile = new ZipFile(context.getPackageResourcePath())) {
                cacheApkPath = context.getCacheDir() + "/lspatch/origin/" + sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH).getCrc() + ".apk";
            }
            org.lsposed.lspd.nativebridge.SigBypass.enableOpenatHook(context.getPackageResourcePath(), cacheApkPath);
        }
    }
}
