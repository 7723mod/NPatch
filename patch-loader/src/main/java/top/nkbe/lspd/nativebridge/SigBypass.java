package org.lsposed.lspd.nativebridge;

public class SigBypass {
    public static native void enableOpenatHook(String patchedApkPath, String originalApkPath, String packageName);
    public static native void disableOpenatHook();
}
