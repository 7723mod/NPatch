package top.nkbe.npatch.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import org.matrix.vector.ipc.IRemotePreferenceCallback;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Canonical SQLite-backed storage and validation backend for NPatch remote preferences and files.
 *
 * <p>Preserves full object type fidelity (including {@code Set<String>} and arbitrary Serializables)
 * across IPC boundaries, mirroring the Vector daemon's schema while operating in rootless user mode.</p>
 */
public final class NPatchRemoteStore {
    public static final long CAP_REMOTE = 1L << 1;
    private static final String TAG = "NPatchRemoteStore";
    private static final String DB_NAME = "npatch-xposed-remote.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "configs";
    private static final int PER_USER_RANGE = 100000;

    private static final Map<String, NPatchRemoteStore> INSTANCES = new ConcurrentHashMap<>();

    private static final class CallbackState {
        final int userId;
        final IRemotePreferenceCallback callback;
        final IBinder.DeathRecipient deathRecipient;

        CallbackState(int userId, IRemotePreferenceCallback callback, IBinder.DeathRecipient deathRecipient) {
            this.userId = userId;
            this.callback = callback;
            this.deathRecipient = deathRecipient;
        }
    }

    private final Context context;
    private final String modulePackageName;
    private final DatabaseHelper dbHelper;
    private final Map<String, Set<CallbackState>> groupCallbacks = new ConcurrentHashMap<>();

    private NPatchRemoteStore(Context context, String modulePackageName) {
        Context appContext = context.getApplicationContext();
        this.context = appContext == null ? context : appContext;
        this.modulePackageName = requireModulePackage(modulePackageName);
        this.dbHelper = new DatabaseHelper(this.context);
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

    private static int callingUserId() {
        return Binder.getCallingUid() / PER_USER_RANGE;
    }

    public Bundle requestPreferences(String group, IRemotePreferenceCallback callback) {
        int userId = callingUserId();
        String safeGroup = safeStorageName(group, "preference group");
        HashMap<String, Object> snapshot = new HashMap<>(getModulePrefs(userId, safeGroup));
        if (callback != null) {
            registerCallback(safeGroup, userId, callback);
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
        int userId = callingUserId();
        String safeGroup = safeStorageName(group, "preference group");

        if (diff.getBoolean("clear", false)) {
            deleteModulePrefs(userId, safeGroup);
        }

        Map<String, Object> values = new HashMap<>();
        Serializable deletes = diff.getSerializable("delete");
        if (deletes instanceof Set<?>) {
            for (Object key : (Set<?>) deletes) {
                if (key instanceof String) {
                    values.put((String) key, null);
                }
            }
        }

        Serializable puts = diff.getSerializable("put");
        if (puts instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) puts).entrySet()) {
                if (entry.getKey() instanceof String) {
                    values.put((String) entry.getKey(), entry.getValue());
                }
            }
        }

        applyPrefsDiff(userId, safeGroup, values);
        notifyPreferenceChanges(safeGroup, userId, diff);
    }

    public void deletePreferences(String group) throws RemoteException {
        int userId = callingUserId();
        String safeGroup = safeStorageName(group, "preference group");
        deleteModulePrefs(userId, safeGroup);
        Bundle clearDiff = new Bundle();
        clearDiff.putBoolean("clear", true);
        notifyPreferenceChanges(safeGroup, userId, clearDiff);
    }

    public Map<String, Object> getModulePrefs(int userId, String group) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Cursor cursor = dbHelper.getReadableDatabase().query(
                TABLE, new String[]{"`key`", "data"},
                "module_pkg_name = ? AND user_id = ? AND `group` = ?",
                new String[]{modulePackageName, Integer.toString(userId), group},
                null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                Object value = deserialize(cursor.getBlob(1));
                if (value != null) {
                    result.put(cursor.getString(0), value);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getModulePrefs error for " + modulePackageName + "/" + group, t);
        }
        if (result.isEmpty() && userId != 0) {
            try (Cursor cursor = dbHelper.getReadableDatabase().query(
                    TABLE, new String[]{"`key`", "data"},
                    "module_pkg_name = ? AND user_id = 0 AND `group` = ?",
                    new String[]{modulePackageName, group},
                    null, null, null)) {
                while (cursor != null && cursor.moveToNext()) {
                    Object value = deserialize(cursor.getBlob(1));
                    if (value != null) {
                        result.put(cursor.getString(0), value);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "getModulePrefs fallback to user 0 error for " + modulePackageName + "/" + group, t);
            }
        }
        return result;
    }

    private void applyPrefsDiff(int userId, String group, Map<String, Object> diff) throws RemoteException {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (Map.Entry<String, Object> entry : diff.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Serializable) {
                    ContentValues values = new ContentValues();
                    values.put("module_pkg_name", modulePackageName);
                    values.put("user_id", userId);
                    values.put("`group`", group);
                    values.put("`key`", key);
                    values.put("data", serialize((Serializable) value));
                    db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                } else {
                    db.delete(TABLE,
                            "module_pkg_name = ? AND user_id = ? AND `group` = ? AND `key` = ?",
                            new String[]{modulePackageName, Integer.toString(userId), group, key});
                }
            }
            db.setTransactionSuccessful();
        } catch (Throwable t) {
            throw new RemoteException("Failed to persist remote preferences: " + t.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    private void deleteModulePrefs(Integer userId, String group) {
        StringBuilder where = new StringBuilder("module_pkg_name = ?");
        List<String> args = new ArrayList<>();
        args.add(modulePackageName);
        if (userId != null) {
            where.append(" AND user_id = ?");
            args.add(Integer.toString(userId));
        }
        if (group != null) {
            where.append(" AND `group` = ?");
            args.add(group);
        }
        try {
            dbHelper.getWritableDatabase().delete(TABLE, where.toString(), args.toArray(new String[0]));
        } catch (Throwable t) {
            Log.w(TAG, "deleteModulePrefs error for " + modulePackageName, t);
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

    private void registerCallback(String group, int userId, IRemotePreferenceCallback callback) {
        IBinder binder = callback.asBinder();
        Set<CallbackState> callbacks = groupCallbacks.computeIfAbsent(group, g -> ConcurrentHashMap.newKeySet());
        CallbackState[] stateHolder = new CallbackState[1];
        IBinder.DeathRecipient recipient = () -> {
            if (stateHolder[0] != null) {
                callbacks.remove(stateHolder[0]);
            }
        };
        CallbackState state = new CallbackState(userId, callback, recipient);
        stateHolder[0] = state;
        callbacks.add(state);
        try {
            binder.linkToDeath(recipient, 0);
        } catch (RemoteException exception) {
            callbacks.remove(state);
        }
    }

    private void notifyPreferenceChanges(String group, int userId, Bundle diff) {
        Set<CallbackState> callbacks = groupCallbacks.get(group);
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        for (CallbackState state : callbacks) {
            if (state.userId != userId) continue;
            try {
                state.callback.onRemotePreferencesChanged(diff);
            } catch (RemoteException exception) {
                callbacks.remove(state);
            }
        }
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
            throw new IllegalArgumentException("Invalid module package name");
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

    private static byte[] serialize(Serializable value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        } catch (Throwable t) {
            Log.w(TAG, "serialize", t);
            return null;
        }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] blob) {
        if (blob == null) return null;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(blob))) {
            return in.readObject();
        } catch (Throwable t) {
            Log.w(TAG, "deserialize", t);
            return null;
        }
    }

    private static final class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                    + "module_pkg_name TEXT NOT NULL,"
                    + "user_id INTEGER NOT NULL,"
                    + "`group` TEXT NOT NULL,"
                    + "`key` TEXT NOT NULL,"
                    + "data BLOB,"
                    + "PRIMARY KEY (module_pkg_name, user_id, `group`, `key`))");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
