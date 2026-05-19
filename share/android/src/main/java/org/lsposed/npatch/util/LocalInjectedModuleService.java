package top.nkbe.npatch.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.service.IRemotePreferenceCallback;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LocalInjectedModuleService extends ILSPInjectedModuleService.Stub {
    private static final long PROP_CAP_REMOTE = 1L << 1;

    private static final class CallbackState {
        final IRemotePreferenceCallback callback;
        Map<String, Object> lastSnapshot;

        CallbackState(IRemotePreferenceCallback callback, Map<String, Object> lastSnapshot) {
            this.callback = callback;
            this.lastSnapshot = lastSnapshot;
        }
    }

    private final Context context;
    private final String packageName;
    private final Map<String, PreferenceGroupState> preferenceGroups = new ConcurrentHashMap<>();

    private final class PreferenceGroupState {
        final SharedPreferences preferences;
        final Map<IBinder, CallbackState> callbacks = new ConcurrentHashMap<>();
        final SharedPreferences.OnSharedPreferenceChangeListener listener;

        PreferenceGroupState(String group) {
            preferences = context.getSharedPreferences(preferencesName(group), Context.MODE_PRIVATE);
            listener = (sharedPreferences, key) -> notifyPreferenceChanges(this);
            preferences.registerOnSharedPreferenceChangeListener(listener);
        }
    }

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
        PreferenceGroupState groupState =
                preferenceGroups.computeIfAbsent(safeName(group), ignored -> new PreferenceGroupState(group));
        HashMap<String, Object> snapshot = snapshotPreferences(groupState.preferences);
        if (callback != null) {
            groupState.callbacks.put(
                    callback.asBinder(),
                    new CallbackState(callback, new HashMap<>(snapshot)));
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

    private void notifyPreferenceChanges(PreferenceGroupState groupState) {
        HashMap<String, Object> currentSnapshot = snapshotPreferences(groupState.preferences);
        List<Map.Entry<IBinder, CallbackState>> callbackEntries = new ArrayList<>(groupState.callbacks.entrySet());
        for (Map.Entry<IBinder, CallbackState> callbackEntry : callbackEntries) {
            CallbackState callbackState = callbackEntry.getValue();
            Bundle diff = buildDiffBundle(callbackState.lastSnapshot, currentSnapshot);
            callbackState.lastSnapshot = new HashMap<>(currentSnapshot);
            if (diff.isEmpty()) {
                continue;
            }
            try {
                callbackState.callback.onUpdate(diff);
            } catch (RemoteException e) {
                groupState.callbacks.remove(callbackEntry.getKey());
            }
        }
    }

    private static HashMap<String, Object> snapshotPreferences(SharedPreferences preferences) {
        HashMap<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Serializable) {
                snapshot.put(entry.getKey(), value);
            }
        }
        return snapshot;
    }

    private static Bundle buildDiffBundle(Map<String, Object> previous, Map<String, Object> current) {
        Set<String> deleted = new HashSet<>();
        HashMap<String, Object> updated = new HashMap<>();

        for (String key : previous.keySet()) {
            if (!current.containsKey(key)) {
                deleted.add(key);
            }
        }
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            if (!Objects.equals(previous.get(entry.getKey()), entry.getValue())) {
                updated.put(entry.getKey(), entry.getValue());
            }
        }

        Bundle bundle = new Bundle();
        if (!deleted.isEmpty()) {
            bundle.putSerializable("delete", new HashSet<>(deleted));
        }
        if (!updated.isEmpty()) {
            bundle.putSerializable("put", updated);
        }
        return bundle;
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
