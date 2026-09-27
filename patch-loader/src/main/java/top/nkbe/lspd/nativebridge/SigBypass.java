package org.lsposed.lspd.nativebridge;

public class SigBypass {
    public static native void enableOpenatHook(String patchedApkPath, String originalApkPath, String packageName, boolean hideLibs);
    public static native void enableOpenatHookMinimal(String patchedApkPath, String originalApkPath, String packageName, boolean hideLibs);
    public static native void setModuleNativeLibraryRoots(String[] roots);
    public static native void disableOpenatHook();
}