package top.nkbe.npatch.util;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.IRemotePreferenceCallback;

/**
 * Read-only injected-LoadedModule view of an {@link NPatchRemoteStore}.
 *
 * <p>Vector API 101/102 deliberately keeps injected remote data read-only. LoadedModule applications
 * perform writes through {@code IXposedService}; this service only delivers snapshots, change
 * callbacks and read-only files to code running inside hooked targets.</p>
 */
public final class LocalInjectedModuleService extends IModuleService.Stub {
    private final NPatchRemoteStore store;
    private final int allowedUid;

    public LocalInjectedModuleService(Context context, String packageName) {
        this(context, packageName, -1);
    }

    public LocalInjectedModuleService(Context context, String packageName, int allowedUid) {
        store = NPatchRemoteStore.get(context, packageName);
        this.allowedUid = allowedUid;
    }

    @Override
    public long getFrameworkProperties() {
        enforceCaller();
        return NPatchRemoteStore.CAP_REMOTE;
    }

    @Override
    public Bundle requestRemotePreferences(
            String group,
            IRemotePreferenceCallback callback
    ) {
        enforceCaller();
        return store.requestPreferences(group, callback);
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        enforceCaller();
        return store.openFile(path, false);
    }

    @Override
    public String[] getRemoteFileNames() {
        enforceCaller();
        return store.listFiles();
    }

    private void enforceCaller() {
        if (allowedUid >= 0 && Binder.getCallingUid() != allowedUid) {
            throw new SecurityException("Remote service binder was passed to another UID");
        }
    }
}
