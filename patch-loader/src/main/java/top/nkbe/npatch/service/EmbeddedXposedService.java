package top.nkbe.npatch.service;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.IHotReloadCallback;
import io.github.libxposed.service.IXposedScopeCallback;
import io.github.libxposed.service.IXposedService;
import top.nkbe.npatch.share.LSPConfig;
import top.nkbe.npatch.util.NPatchRemoteStore;

/**
 * In-process implementation of {@link IXposedService} for modern modules running in embedded mode.
 */
public class EmbeddedXposedService extends IXposedService.Stub {
    private static final String TAG = "EmbeddedXposedService";

    private final String modulePackageName;
    private final String hostPackageName;
    private final NPatchRemoteStore store;

    public EmbeddedXposedService(Context context, String modulePackageName, String hostPackageName) {
        this.modulePackageName = modulePackageName;
        this.hostPackageName = hostPackageName;
        this.store = NPatchRemoteStore.get(context, modulePackageName);
    }

    @Override
    public int getApiVersion() {
        return IXposedService.API_102;
    }

    @Override
    public String getFrameworkName() {
        return "NPatch";
    }

    @Override
    public String getFrameworkVersion() {
        String ver = LSPConfig.instance.VERSION_NAME;
        return ver.startsWith("v") ? ver : "v" + ver;
    }

    @Override
    public long getFrameworkVersionCode() {
        return LSPConfig.instance.VERSION_CODE;
    }

    @Override
    public long getFrameworkProperties() {
        return NPatchRemoteStore.CAP_REMOTE;
    }

    @Override
    public List<String> getScope() {
        return Collections.singletonList(hostPackageName);
    }

    @Override
    public void requestScope(List<String> packages, IXposedScopeCallback callback) {
        try {
            if (callback != null) {
                callback.onScopeRequestFailed("scope is set by patching in NPatch; it cannot be granted at runtime");
            }
        } catch (Throwable t) {
            Log.w(TAG, "onScopeRequestFailed error", t);
        }
    }

    @Override
    public void removeScope(List<String> packages) {
    }

    @Override
    public List<HookedProcess> getRunningTargets() {
        return Collections.emptyList();
    }

    @Override
    public void hotReloadModule(long targetId, Bundle data, IHotReloadCallback callback) {
        try {
            if (callback != null) {
                callback.onHotReloadResult(IXposedService.HOT_RELOAD_UNSUPPORTED,
                        "hot reload is unsupported for integrated modules");
            }
        } catch (Throwable t) {
            Log.w(TAG, "hotReloadModule callback error", t);
        }
    }

    @Override
    public Bundle requestRemotePreferences(String group) {
        return store.requestPreferences(group, null);
    }

    @Override
    public void updateRemotePreferences(String group, Bundle diff) throws RemoteException {
        store.updatePreferences(group, diff);
    }

    @Override
    public void deleteRemotePreferences(String group) throws RemoteException {
        store.deletePreferences(group);
    }

    @Override
    public String[] listRemoteFiles() {
        return store.listFiles();
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String name) throws RemoteException {
        return store.openFile(name, true);
    }

    @Override
    public boolean deleteRemoteFile(String name) {
        return store.deleteFile(name);
    }
}
