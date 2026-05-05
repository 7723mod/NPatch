package top.nkbe.npatch.loader.util;

import top.nkbe.npatch.loader.BuildConfig;

import de.robv.android.xposed.XposedBridge;

public class XLog {

    private static boolean enableLog = BuildConfig.DEBUG;
    private static volatile boolean mirrorToMedia = false;
    private static String targetPackageName = null;
    private static boolean isTargetProcess = false;

    public static void init(String targetPkg, String currentProc, boolean enableMirror) {
        targetPackageName = targetPkg;
        isTargetProcess = targetPkg != null && targetPkg.equals(currentProc);
        mirrorToMedia = enableMirror;
    }

    public static void d(String tag, String msg) {
        if (enableLog) {
            android.util.Log.d(tag, msg);
        }
        mirror("D", tag, msg, null);
    }

    public static void v(String tag, String msg) {
        if (enableLog) {
            android.util.Log.v(tag, msg);
        }
        mirror("V", tag, msg, null);
    }

    public static void w(String tag, String msg) {
        if (enableLog) {
            android.util.Log.w(tag, msg);
        }
        mirror("W", tag, msg, null);
    }

    public static void i(String tag, String msg) {
        if (enableLog) {
            android.util.Log.i(tag, msg);
        }
        mirror("I", tag, msg, null);
    }

    public static void e(String tag, String msg) {
        if (enableLog) {
            android.util.Log.e(tag, msg);
        }
        mirror("E", tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable tr) {
        if (enableLog) {
            android.util.Log.e(tag, msg, tr);
        }
        mirror("E", tag, msg, tr);
    }

    private static void mirror(String level, String tag, String msg, Throwable tr) {
        if (!mirrorToMedia || !isTargetProcess) {
            return;
        }

        // Whitelist filtering
        boolean isWhitelisted = (tag != null && tag.startsWith("NPatch")) ||
                (targetPackageName != null && targetPackageName.equals(tag));

        if (!isWhitelisted) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        builder.append('[').append(level).append("] ")
                .append(tag).append(": ")
                .append(msg);
        if (tr != null) {
            builder.append('\n').append(android.util.Log.getStackTraceString(tr));
        }
        XposedBridge.log(builder.toString());
    }
}
