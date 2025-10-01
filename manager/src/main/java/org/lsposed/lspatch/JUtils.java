package org.lsposed.lspatch;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.Process;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class JUtils {
    public static String getInstallSign(Context context,String packageName){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            return info.signingInfo.getApkContentsSigners()[0].toCharsString();
        }catch (Exception e){
            Log.e("NPatch", "getInstallSign failed for " + packageName + ": " + Log.getStackTraceString(e));
            return "";
        }

    }

    public static String getApkSign(Context context,String apkPath){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNING_CERTIFICATES);
            return info.signingInfo.getApkContentsSigners()[0].toCharsString();
        }catch (Exception e){
            Log.e("NPatch", "getApkSign failed for " + apkPath + ": " + Log.getStackTraceString(e));
            return "";
        }
    }

    public static boolean checkSignMatched(Context context,String packageName,String apkPath){
        return getInstallSign(context,packageName).equals(getApkSign(context,apkPath));
    }

    // 通过检查其元数据中是否包含"lspatch", 如果元数据不存在或不包含"lspatch"键，则返回 true (未被修补)
    public static boolean checkIsApkFixedByLSP(Context context,String packageName){
        try {
            PackageManager manager = context.getPackageManager();
            ApplicationInfo info = manager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            return info.metaData == null || !info.metaData.containsKey("lspatch");
        }catch (Exception e){
            Log.i("NPatch",Log.getStackTraceString(e));
            return false;
        }
    }

    public static void installApkByPackageManager(Context context,File apkPath){
        try {
            Log.i("NPatchOutput", "RequestInstall: " + apkPath);
            String e = context.getExternalCacheDir() + "/install.apk";
            File file = new File(e);
            if (file.exists()) {
                file.delete();
            }
            // 复制文件到缓存目录，以便 FileProvider 访问
            copy(apkPath.getAbsolutePath(), e);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // 使用 FileProvider 获取 Uri，适用于 Android N 及以上版本
            Uri apkUriN = FileProvider.getUriForFile(context,
                    context.getApplicationContext().getPackageName() + ".FileProvider", file);
            intent.addCategory("android.intent.category.DEFAULT");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setDataAndType(apkUriN, "application/vnd.android.package-archive");

            context.startActivity(intent);
        }catch (Exception e){
            Log.i("NPatchOutput", Log.getStackTraceString(e));
        }
    }

    public static void copy(String source, String dest) {

        try {
            if (!new File(source).exists()){
                return;
            }

            File f = new File(dest);
            f = f.getParentFile();
            if (!f.exists()) f.mkdirs();

            File aaa = new File(dest);
            if (aaa.exists()) aaa.delete();

            InputStream in = Files.newInputStream(new File(source).toPath());
            OutputStream out = Files.newOutputStream(new File(dest).toPath());
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            in.close();
            out.close();
        } catch (Exception ignored) {
        }
    }

    public static void uninstallApkByPackageName(Context context,String packageName){
        try {
            Uri packageURI = Uri.parse("package:" + packageName);
            Intent uninstallIntent = new Intent(Intent.ACTION_DELETE, packageURI);
            uninstallIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(uninstallIntent);
        }catch (Exception ignored){
        }
    }

    // 如果应用是一个 LSPatch 产物（包含 assets/lspatch/origin.apk）, 则解压出原始安装包到缓存目录來替换列表中的路径。
    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static List<String> processApkPath(Context context, List<String> paths){
        Log.i("NPatchOutput", "processApkPath: " + paths.toString());
        if (paths.size() == 1){
            String apkPath = paths.get(0);
            try (ZipFile zInp = new ZipFile(apkPath)){
                ZipEntry entry = zInp.getEntry("assets/lspatch/origin.apk");

                Log.i("NPatchOutput", "processApkPath: " + entry);
                if (entry != null){
                    String cachePath = context.getCacheDir().getAbsolutePath() + File.separator + "lspatch" + File.separator + System.currentTimeMillis()+".apk";
                    File newFile = new File(cachePath).getParentFile();
                    if (!newFile.exists()){
                        newFile.mkdirs();
                    }
                    FileOutputStream cacheFile = new FileOutputStream(cachePath);
                    FileUtils.copy(zInp.getInputStream(entry),cacheFile);
                    cacheFile.close();
                    paths.set(0,cachePath);
                }
            } catch (IOException e) {
                Log.i("NPatchOutput", "processApkPath: " + e);
                return paths;
            }
            return paths;

        }else {
            return paths;
        }
    }

    // 添加一个通用的方法来获取任意包名的版本信息
    public static String getAppVersionName(Context context, String packageName){
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(packageName, 0);
            return info.versionName;
        }catch (Exception ignored){
            return "";
        }
    }

}