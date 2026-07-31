package top.nkbe.npatch;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface INPatchShizukuService {
    Bundle installApks(in ParcelFileDescriptor[] apkFiles, in String[] names, String packageName, long totalSize, int userId) = 1;
    Bundle uninstallPackage(String packageName, int userId) = 2;
    boolean performDexOptMode(String packageName, int userId) = 3;
    void destroy() = 4;
}
