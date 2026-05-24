package org.lsposed.lspd.nativebridge;

public class FunPatch {
    public static native boolean enableSeccompV2Redirect(String currentPath, String originalPath, String packageName);
}
