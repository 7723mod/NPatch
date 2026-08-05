package top.nkbe.npatch.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import org.matrix.vector.ipc.IRemotePreferenceCallback;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical storage and validation backend for NPatch remote preferences and files.
 *
 * <p>The injected read-only service and the LoadedModule-app {@code IXposedService} adapter delegate to
 * this class. Instances are scoped to the hosting application's data directory and LoadedModule package,
 * so manager-backed services share one store while standalone local mode remains self-contained.</p>
 */
public final class NPatchRemoteStore {
    public static final long CAP_REMOTE = 1L << 1;

    private static final Map<String, NPatchRemoteStore> INSTANCES = new ConcurrentHashMap<>();

    private static final class CallbackState {
        final IRemotePreferenceCallback callback;
        final IBinder.DeathRecipient deathRecipient;
        Map<String, Object> lastSnapshot;

        CallbackState(
                IRemotePreferenceCallback callback,
                IBinder.DeathRecipient deathRecipient,
                Map<String, Object> lastSnapshot
        ) {
            this.callback = callback;
            this.deathRecipient = deathRecipient;
            this.lastSnapshot = lastSnapshot;
        }
    }

    private final Context context;
    private final String modulePackageName;
    private final Map<String, PreferenceGroupState> preferenceGroups = new ConcurrentHashMap<>();

    private final class PreferenceGroupState {
        final SharedPreferences preferences;
        final Map<IBinder, CallbackState> callbacks = new ConcurrentHashMap<>();
        final SharedPreferences.OnSharedPreferenceChangeListener listener;

        PreferenceGroupState(String safeGroup) {
            preferences =
                    context.getSharedPreferences(preferencesName(safeGroup), Context.MODE_PRIVATE);
            listener = (sharedPreferences, key) -> notifyPreferenceChanges(this);
            preferences.registerOnSharedPreferenceChangeListener(listener);
        }
    }

    private NPatchRemoteStore(Context context, String modulePackageName) {
        Context appContext = context.getApplicationContext();
        this.context = appContext == null ? context : appContext;
        this.modulePackageName = requireModulePackage(modulePackageName);
    }

    public static NPatchRemoteStore get(Context context, String modulePackageName) {
        Objects.requireNonNull(context, "context");
        Context appContext = context.getApplicationContext();
        Context storageContext = appContext == null ? context : appContext;
        String safePackage = requireModulePackage(modulePackageName);
        String key = storageContext.getApplicationInfo().dataDir + ':' + safePackage;
        return INSTANCES.computeIfAbsent(
                key, ignored -> new NPatchRemoteStore(storageContext, safePackage));
    }

    public Bundle requestPreferences(String group, IRemotePreferenceCallback callback) {
        PreferenceGroupState state = preferenceGroup(group);
        HashMap<String, Object> snapshot = snapshotPreferences(state.preferences);
        if (callback != null) {
            registerCallback(state, callback, snapshot);
        }
        Bundle result = new Bundle();
        result.putSerializable("map", snapshot);
        return result;
    }

    @SuppressWarnings("deprecation")
    public void updatePreferences(String group, Bundle diff) throws RemoteException {
        if (diff == null) {
            throw new RemoteException("Remote preference diff is null");
        }
        SharedPreferences.Editor editor = preferenceGroup(group).preferences.edit();
        if (diff.getBoolean("clear", false)) {
            editor.clear();
        }

        Serializable deletes = diff.getSerializable("delete");
        if (deletes instanceof Set<?>) {
            for (Object key : (Set<?>) deletes) {
                if (key instanceof String) {
                    editor.remove((String) key);
                }
            }
        }

        Serializable puts = diff.getSerializable("put");
        if (puts instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) puts).entrySet()) {
                if (entry.getKey() instanceof String) {
                    putValue(editor, (String) entry.getKey(), entry.getValue());
                }
            }
        }
        if (!editor.commit()) {
            throw new RemoteException("Failed to persist remote preferences");
        }
    }

    public void deletePreferences(String group) throws RemoteException {
        if (!preferenceGroup(group).preferences.edit().clear().commit()) {
            throw new RemoteException("Failed to delete remote preferences");
        }
    }

    public String[] listFiles() {
        String[] files = remoteFilesDir().list((dir, name) -> new File(dir, name).isFile());
        if (files == null) {
            return new String[0];
        }
        Arrays.sort(files);
        return files;
    }

    public ParcelFileDescriptor openFile(String name, boolean writable) throws RemoteException {
        File file = resolveRemoteFile(name);
        if (!writable && !file.isFile()) {
            return null;
        }
        if (writable) {
            File directory = remoteFilesDir();
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new RemoteException("Cannot create remote file directory");
            }
        }
        int mode = writable
                ? ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE
                : ParcelFileDescriptor.MODE_READ_ONLY;
        try {
            return ParcelFileDescriptor.open(file, mode);
        } catch (Throwable throwable) {
            RemoteException error = new RemoteException("Cannot open remote file: " + name);
            error.initCause(throwable);
            throw error;
        }
    }

    public boolean deleteFile(String name) {
        try {
            return resolveRemoteFile(name).delete();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private PreferenceGroupState preferenceGroup(String group) {
        String safeGroup = safeStorageName(group, "preference group");
        return preferenceGroups.computeIfAbsent(safeGroup, PreferenceGroupState::new);
    }

    private void registerCallback(
            PreferenceGroupState state,
            IRemotePreferenceCallback callback,
        Map<String, Object> snapshot
    ) {
        IBinder binder = callback.asBinder();
        CallbackState[] callbackHolder = new CallbackState[1];
        IBinder.DeathRecipient recipient =
                () -> removeCallback(state, binder, callbackHolder[0]);
        CallbackState callbackState =
                new CallbackState(callback, recipient, new HashMap<>(snapshot));
        callbackHolder[0] = callbackState;
        CallbackState previous = state.callbacks.put(binder, callbackState);
        if (previous != null) {
            binder.unlinkToDeath(previous.deathRecipient, 0);
        }
        try {
            binder.linkToDeath(recipient, 0);
        } catch (RemoteException exception) {
            state.callbacks.remove(binder, callbackState);
        }
    }

    private void removeCallback(
            PreferenceGroupState state,
            IBinder binder,
            CallbackState expected
    ) {
        CallbackState removed;
        if (expected == null) {
            removed = state.callbacks.remove(binder);
        } else {
            removed = state.callbacks.remove(binder, expected) ? expected : null;
        }
        if (removed != null) {
            binder.unlinkToDeath(removed.deathRecipient, 0);
        }
    }

    private void notifyPreferenceChanges(PreferenceGroupState state) {
        HashMap<String, Object> current = snapshotPreferences(state.preferences);
        List<Map.Entry<IBinder, CallbackState>> callbacks =
                new ArrayList<>(state.callbacks.entrySet());
        for (Map.Entry<IBinder, CallbackState> entry : callbacks) {
            CallbackState callbackState = entry.getValue();
            Bundle diff;
            synchronized (callbackState) {
                diff = buildDiffBundle(callbackState.lastSnapshot, current);
                callbackState.lastSnapshot = new HashMap<>(current);
            }
            if (diff.isEmpty()) {
                continue;
            }
            try {
                callbackState.callback.onRemotePreferencesChanged(diff);
            } catch (RemoteException exception) {
                removeCallback(state, entry.getKey(), callbackState);
            }
        }
    }

    private String preferencesName(String safeGroup) {
        return "npatch_remote_" + modulePackageName + '_' + safeGroup;
    }

    private File remoteFilesDir() {
        return new File(context.getFilesDir(), "npatch/remote/" + modulePackageName);
    }

    private File resolveRemoteFile(String name) {
        String safeName = requireFlatName(name, "remote file name");
        return new File(remoteFilesDir(), safeName);
    }

    private static String requireModulePackage(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException("Invalid LoadedModule package name");
        }
        return value;
    }

    private static String requireFlatName(String value, String label) {
        if (value == null || value.isEmpty() || value.equals(".") || value.equals("..")
                || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return value;
    }

    private static String safeStorageName(String value, String label) {
        String checked = requireFlatName(value, label);
        return checked.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void putValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set<?>) {
            HashSet<String> strings = new HashSet<>();
            for (Object item : (Set<?>) value) {
                if (!(item instanceof String)) {
                    throw new IllegalArgumentException(
                            "Remote string set contains a non-string value");
                }
                strings.add((String) item);
            }
            editor.putStringSet(key, strings);
        } else {
            throw new IllegalArgumentException("Unsupported remote preference value");
        }
    }

    private static HashMap<String, Object> snapshotPreferences(
            SharedPreferences preferences
    ) {
        HashMap<String, Object> snapshot = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) {
                snapshot.put(entry.getKey(), new HashSet<>((Set<?>) value));
            } else if (value instanceof Serializable) {
                snapshot.put(entry.getKey(), value);
            }
        }
        return snapshot;
    }

    private static Bundle buildDiffBundle(
            Map<String, Object> previous,
            Map<String, Object> current
    ) {
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
        Bundle diff = new Bundle();
        if (!deleted.isEmpty()) {
            diff.putSerializable("delete", new HashSet<>(deleted));
        }
        if (!updated.isEmpty()) {
            diff.putSerializable("put", updated);
        }
        return diff;
    }
}
