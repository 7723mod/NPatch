package org.lsposed.npatch.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.service.IRemotePreferenceCallback;

import java.io.File;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public final class LocalInjectedModuleService extends ILSPInjectedModuleService.Stub {
    private static final long PROP_CAP_REMOTE = 1L << 1;

    private final Context context;
    private final String packageName;

    public LocalInjectedModuleService(Context context, String packageName) {
        Context appContext = context.getApplicationContext();
        this.context = appContext == null ? context : appContext;
        this.packageName = packageName;
    }

    @Override
    public long getFrameworkProperties() {
        return PROP_CAP_REMOTE;
    }

    @Override
    public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) {
        SharedPreferences preferences = context.getSharedPreferences(preferencesName(group), Context.MODE_PRIVATE);
        HashMap<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Serializable) {
                snapshot.put(entry.getKey(), value);
            }
        }
        Bundle bundle = new Bundle();
        bundle.putSerializable("map", snapshot);
        return bundle;
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        if (!isSafeRelativePath(path)) {
            return null;
        }
        File file = new File(remoteFilesDir(), path);
        if (!file.isFile()) {
            return null;
        }
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (Throwable t) {
            RemoteException e = new RemoteException("Cannot open remote file: " + path);
            e.initCause(t);
            throw e;
        }
    }

    @Override
    public String[] getRemoteFileList() {
        String[] files = remoteFilesDir().list();
        return files == null ? new String[0] : files;
    }

    private String preferencesName(String group) {
        return "npatch_remote_" + safeName(packageName) + "_" + safeName(group);
    }

    private File remoteFilesDir() {
        return new File(context.getFilesDir(), "npatch/remote/" + safeName(packageName));
    }

    private static boolean isSafeRelativePath(String path) {
        return path != null
                && !path.isEmpty()
                && !path.equals(".")
                && !path.equals("..")
                && path.indexOf('/') < 0
                && path.indexOf('\\') < 0;
    }

    private static String safeName(String name) {
        if (name == null || name.isEmpty()) {
            return "_";
        }
        return name.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
