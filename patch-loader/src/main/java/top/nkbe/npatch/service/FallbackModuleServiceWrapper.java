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
    private final IModuleService remoteService;
    private final LocalInjectedModuleService localService;
    private final NPatchRemoteStore localStore;
    private boolean isRemoteDead = false;

    public FallbackModuleServiceWrapper(Context context, String modulePackageName, IModuleService remoteService) {
        this.remoteService = remoteService;
        // The fallback local service
        this.localService = new LocalInjectedModuleService(context, modulePackageName);
        // The store used to cache/sync preferences
        this.localStore = NPatchRemoteStore.get(context, modulePackageName);
    }

    @Override
    public long getFrameworkProperties() throws RemoteException {
        if (!isRemoteDead) {
            try {
                return remoteService.getFrameworkProperties();
            } catch (RemoteException e) {
                markRemoteDead(e);
            }
        }
        return localService.getFrameworkProperties();
    }

    @Override
    public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) throws RemoteException {
        if (!isRemoteDead) {
            try {
                // Wrap callback to intercept dynamic updates pushed from the Manager
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

                Bundle result = remoteService.requestRemotePreferences(group, wrappedCallback);
                
                // Cache the initial snapshot locally
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
        
        // Fallback to local storage
        Log.d(TAG, "Using local fallback for requestRemotePreferences: " + group);
        return localService.requestRemotePreferences(group, callback);
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) throws RemoteException {
        if (!isRemoteDead) {
            try {
                return remoteService.openRemoteFile(path);
            } catch (RemoteException e) {
                markRemoteDead(e);
            }
        }
        Log.d(TAG, "Using local fallback for openRemoteFile: " + path);
        return localService.openRemoteFile(path);
    }

    @Override
    public String[] getRemoteFileNames() throws RemoteException {
        if (!isRemoteDead) {
            try {
                return remoteService.getRemoteFileNames();
            } catch (RemoteException e) {
                markRemoteDead(e);
            }
        }
        return localService.getRemoteFileNames();
    }

    private void markRemoteDead(RemoteException e) {
        if (!isRemoteDead) {
            Log.w(TAG, "Remote Manager Service died, downgrading to local service mode", e);
            isRemoteDead = true;
        }
    }
}
