package top.nkbe.npatch.loader.util;

import top.nkbe.npatch.loader.BuildConfig;
import top.nkbe.npatch.loader.XposedLogPrinter;

public class XLog {

    private static boolean enableLog = BuildConfig.DEBUG;
    private static volatile boolean mirrorToMedia = false;
    private static String targetPackageName = null;
    private static boolean isTargetProcess = false;

    public static void init(String targetPkg, String currentProc, boolean enableMirror) {
        targetPackageName = targetPkg;
        isTargetProcess = targetPkg != null
                && currentProc != null
                && (targetPkg.equals(currentProc) || currentProc.startsWith(targetPkg + ":"));
        mirrorToMedia = enableMirror;
    }

    public static void d(String tag, String msg) {
        if (enableLog) {
            android.util.Log.d(tag, msg);
        }
        mirror(android.util.Log.DEBUG, tag, msg, null);
    }

    public static void v(String tag, String msg) {
        if (enableLog) {
            android.util.Log.v(tag, msg);
        }
        mirror(android.util.Log.VERBOSE, tag, msg, null);
    }

    public static void w(String tag, String msg) {
        if (enableLog) {
            android.util.Log.w(tag, msg);
        }
        mirror(android.util.Log.WARN, tag, msg, null);
    }

    public static void i(String tag, String msg) {
        if (enableLog) {
            android.util.Log.i(tag, msg);
        }
        mirror(android.util.Log.INFO, tag, msg, null);
    }

    public static void e(String tag, String msg) {
        if (enableLog) {
            android.util.Log.e(tag, msg);
        }
        mirror(android.util.Log.ERROR, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (enableLog) {
            android.util.Log.e(tag, msg, tr);
        }
        mirror(android.util.Log.ERROR, tag, msg, tr);
    }

    private static void mirror(int priority, String tag, String msg, Throwable tr) {
        if (!mirrorToMedia || !isTargetProcess) {
            return;
        }

        // Whitelist filtering
        boolean isWhitelisted = (tag != null && tag.startsWith("NPatch")) ||
                (targetPackageName != null && targetPackageName.equals(tag));

        if (!isWhitelisted) {
            return;
        }

        XposedLogPrinter.log(priority, tag, msg, tr);
    }
}
