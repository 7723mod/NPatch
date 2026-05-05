package org.lsposed.lspd.nativebridge;

public class SvcBypass {
    // 核心功能方法
    public static native boolean initSvcHook();
    public static native void enableSvcRedirect(String path, String orig, String pkg);
    public static native void disableSvcRedirect();

    // 狀態檢查與除錯方法
    public static native boolean isSvcHookActive();
    public static native void logSvcHookStats();
    public static native String getDebugInfo();

    // 進程與檔案描述符 (FD) 相關方法
    public static native int getCurrentPid();
    public static native int getInitialPid();
    public static native boolean isChildProcess();

    public static native String checkFd(int fd);
    public static native int dupFd(int fd);
    public static native long getFdInode(int fd);
    public static native boolean isSystemFile(int fd);

    // 系統 APK 與證書相關方法
    public static native int findSystemApkFd(String path);
    public static native String[][] getSystemApkFds();
    public static native void refreshSystemFds();

    public static native byte[] readCertificateFromFd(int fd);
    public static native byte[] readCertificateFromPath(String path);
}
