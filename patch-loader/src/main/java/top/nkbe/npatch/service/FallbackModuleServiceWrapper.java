package top.nkbe.npatch.service;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.IRemotePreferenceCallback;
import top.nkbe.npatch.util.LocalInjectedModuleService;
import top.nkbe.npatch.util.NPatchRemoteStore;

public class FallbackModuleServiceWrapper extends IModuleService.Stub {
    private static final String TAG = "NPatch-FallbackWrapper";
    private final Object switchLock = new Object();
    private volatile IModuleService activeRemoteService;
    private final Context context;
    private final LocalInjectedModuleService localService;
    private final NPatchRemoteStore localStore;
    private final String modulePackageName;

    public FallbackModuleServiceWrapper(Context context, String modulePackageName, IModuleService remoteService) {
        this.context = context;
        this.modulePackageName = modulePackageName;
        this.localService = new LocalInjectedModuleService(context, modulePackageName);
        this.localStore = NPatchRemoteStore.get(context, modulePackageName);
        this.activeRemoteService = remoteService;
    }

    public void setRemoteService(IModuleService remoteService) {
        synchronized (switchLock) {
            this.activeRemoteService = remoteService;
            Log.i(TAG, "Switched remote service for " + modulePackageName + " (remoteAvailable=" + (remoteService != null) + ")");
        }
    }

    private IModuleService getActiveRemote() {
        synchronized (switchLock) {
            if (activeRemoteService != null && activeRemoteService.asBinder().isBinderAlive()) {
                return activeRemoteService;
            }
            if (context != null) {
                try {
                    IModuleService bridge = top.nkbe.npatch.util.ManagerRemoteServiceBridge.connect(context, modulePackageName);
                    if (bridge != null && bridge.asBinder().isBinderAlive()) {
                        this.activeRemoteService = bridge;
                        Log.i(TAG, "Lazily connected remote service via Provider bridge for " + modulePackageName);
                        return activeRemoteService;
                    }
                } catch (Throwable t) {
                    // Manager Provider not available or target app not yet in scope
                }
            }
            return null;
        }
    }

    private void markRemoteDead(RemoteException e) {
        synchronized (switchLock) {
            if (activeRemoteService != null) {
                Log.w(TAG, "Remote Manager Service died for " + modulePackageName + ", falling back to local service", e);
                activeRemoteService = null;
            }
        }
    }

    @Override
    public long getFrameworkProperties() throws RemoteException {
        IModuleService remote = getActiveRemote();
        if (remote != null) {
            try {
                return remote.getFrameworkProperties();
            } catch (RemoteException e) {
                markRemoteDead(e);
            }
        }
        return localService.getFrameworkProperties();
    }

    @Override
    public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) throws RemoteException {
        IModuleService remote = getActiveRemote();
        if (remote != null) {
            try {
                IRemotePreferenceCallback wrappedCallback = null;
                if (callback != null) {
                    wrappedCallback = new IRemotePreferenceCallback.Stub() {
                        @Override
                        public void onRemotePreferencesChanged(Bundle diff) throws RemoteException {
                            try {
                                localStore.updatePreferences(group, diff);
                                Log.d(TAG, "Synced dynamic preference update for group: " + group);
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to cache dynamic preference update", e);
                            }
                            callback.onRemotePreferencesChanged(diff);
                        }
                    };
                }

                Bundle result = remote.requestRemotePreferences(group, wrappedCallback);

                if (result != null && result.containsKey("map")) {
                    try {
                        Bundle diff = new Bundle();
                        diff.putBoolean("clear", true);
                        diff.putSerializable("put", result.getSerializable("map"));
                        localStore.updatePreferences(group, diff);
                        Log.d(TAG, "Synced initial preference snapshot for group: " + group);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to cache initial preferences", e);
                    }
                }

                return result;
            } catch (RemoteException e) {
                markRemoteDead(e);
            }
        }

        Log.d(TAG, "Using local fallback for requestRemotePreferences: " + group);
        return localService.requestRemotePreferences(group, callback);
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        IModuleService remote = getActiveRemote();
        if (remote != null) {
            try {
                return remote.openRemoteFile(path);
            } catch (RemoteException e) {
                markRemoteDead(e);
            }
        }
        Log.d(TAG, "Using local fallback for openRemoteFile: " + path);
        return localService.openRemoteFile(path);
    }

    @Override
    public String[] getRemoteFileNames() throws RemoteException {
        IModuleService remote = getActiveRemote();
        if (remote != null) {
            try {
                return remote.getRemoteFileNames();
            } catch (RemoteException e) {
                markRemoteDead(e);
            }
        }
        return localService.getRemoteFileNames();
    }
}

