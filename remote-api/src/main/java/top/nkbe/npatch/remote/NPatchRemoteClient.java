package top.nkbe.npatch.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;

import io.github.libxposed.service.IXposedService;

/**
 * Public NPatch client for module applications that cannot receive the normal libxposed provider
 * callback in local patching mode.
 *
 * <p>Writes use the standard {@link IXposedService} contract. Injected target access is a
 * framework-internal, read-only path and is intentionally not exposed by this module-app SDK.</p>
 */
public final class NPatchRemoteClient {
    public static final String AUTHORITY = "top.nkbe.npatch.remote";

    private static final String METHOD_GET_REMOTE_SERVICE = "getRemoteService";
    private static final String KEY_MODULE_PACKAGE = "modulePackageName";
    private static final String KEY_BINDER = "binder";
    private static final long CONNECT_TIMEOUT_SECONDS = 3;
    private static final ExecutorService CONNECTION_EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "NPatch-RemoteConnect");
                thread.setDaemon(true);
                return thread;
            });

    private final IXposedService service;
    private final Map<String, RemotePreferences> preferences = new ConcurrentHashMap<>();

    private NPatchRemoteClient(IXposedService service) {
        this.service = service;
    }

    public static NPatchRemoteClient connect(Context context) {
        return connect(context, context.getPackageName());
    }

    public static NPatchRemoteClient connect(Context context, String modulePackageName) {
        return new NPatchRemoteClient(connectService(context, modulePackageName));
    }

    public static CompletableFuture<NPatchRemoteClient> connectAsync(Context context) {
        return connectAsync(context, context.getPackageName());
    }

    public static CompletableFuture<NPatchRemoteClient> connectAsync(
            Context context,
            String modulePackageName
    ) {
        return CompletableFuture.supplyAsync(
                () -> connect(context, modulePackageName),
                CONNECTION_EXECUTOR
        );
    }

    public static IXposedService connectService(Context context, String modulePackageName) {
        IBinder binder = requestBinder(context, METHOD_GET_REMOTE_SERVICE, modulePackageName);
        IXposedService service = IXposedService.Stub.asInterface(binder);
        if (service == null) {
            throw new IllegalStateException("NPatch remote service returned an invalid binder");
        }
        return service;
    }

    public SharedPreferences getRemotePreferences(String group) {
        return preferences.computeIfAbsent(group, key -> new RemotePreferences(service, key));
    }

    public void deleteRemotePreferences(String group) throws RemoteException {
        service.deleteRemotePreferences(group);
        RemotePreferences cached = preferences.remove(group);
        if (cached != null) {
            cached.replaceWithEmpty();
        }
    }

    public String[] listRemoteFiles() throws RemoteException {
        return service.listRemoteFiles();
    }

    public ParcelFileDescriptor openRemoteFile(String name)
            throws RemoteException, FileNotFoundException {
        ParcelFileDescriptor descriptor = service.openRemoteFile(name);
        if (descriptor == null) {
            throw new FileNotFoundException(name);
        }
        return descriptor;
    }

    public boolean deleteRemoteFile(String name) throws RemoteException {
        return service.deleteRemoteFile(name);
    }

    private static IBinder requestBinder(
            Context context,
            String method,
            String modulePackageName
    ) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(modulePackageName, "modulePackageName");
        Bundle extras = new Bundle();
        extras.putString(KEY_MODULE_PACKAGE, modulePackageName);
        Future<Bundle> call = CONNECTION_EXECUTOR.submit(
                () -> context.getContentResolver().call(
                        Uri.parse("content://" + AUTHORITY),
                        method,
                        null,
                        extras
                )
        );
        Bundle result;
        try {
            result = call.get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            call.cancel(true);
            throw new IllegalStateException("NPatch remote service connection timed out", exception);
        } catch (InterruptedException exception) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NPatch remote service connection interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SecurityException) {
                throw (SecurityException) cause;
            }
            throw new IllegalStateException("NPatch remote service is unavailable", cause);
        }
        IBinder binder = result == null ? null : result.getBinder(KEY_BINDER);
        if (binder == null) {
            throw new SecurityException(
                    "NPatch rejected the remote service request for " + modulePackageName);
        }
        return binder;
    }

    private static final class RemotePreferences implements SharedPreferences {
        private static final ExecutorService EXECUTOR =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "NPatch-RemoteApi");
                    thread.setDaemon(true);
                    return thread;
                });

        private final IXposedService service;
        private final String group;
        private final Set<OnSharedPreferenceChangeListener> listeners =
                Collections.newSetFromMap(new ConcurrentHashMap<>());
        private volatile Map<String, Object> values;

        @SuppressWarnings("unchecked")
        RemotePreferences(IXposedService service, String group) {
            this.service = service;
            this.group = group;
            try {
                Bundle result = service.requestRemotePreferences(group);
                Serializable map = result == null ? null : result.getSerializable("map");
                if (map instanceof Map<?, ?>) {
                    values = immutableCopy((Map<String, Object>) map);
                } else {
                    values = Collections.emptyMap();
                }
            } catch (RemoteException exception) {
                throw new IllegalStateException("Cannot read NPatch remote preferences", exception);
            }
        }

        @Override
        public Map<String, ?> getAll() {
            return new TreeMap<>(values);
        }

        @Override
        public String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (String) value;
        }

        @SuppressWarnings("unchecked")
        @Override
        public Set<String> getStringSet(String key, Set<String> defaultValues) {
            Object value = values.get(key);
            return value == null
                    ? defaultValues
                    : new HashSet<>((Set<String>) value);
        }

        @Override
        public int getInt(String key, int defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Integer) value;
        }

        @Override
        public long getLong(String key, long defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Long) value;
        }

        @Override
        public float getFloat(String key, float defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Float) value;
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Boolean) value;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new RemoteEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) {
            if (listener != null) {
                listeners.add(listener);
            }
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener
        ) {
            if (listener != null) {
                listeners.remove(listener);
            }
        }

        private void applyDiff(Bundle diff) {
            Set<String> changed = new HashSet<>();
            synchronized (this) {
                Map<String, Object> updated = new HashMap<>(values);
                if (diff.getBoolean("clear", false)) {
                    changed.addAll(updated.keySet());
                    updated.clear();
                }

                Serializable deletes = diff.getSerializable("delete");
                if (deletes instanceof Set<?>) {
                    for (Object item : (Set<?>) deletes) {
                        if (item instanceof String && updated.containsKey(item)) {
                            updated.remove(item);
                            changed.add((String) item);
                        }
                    }
                }

                Serializable puts = diff.getSerializable("put");
                if (puts instanceof Map<?, ?>) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) puts).entrySet()) {
                        if (!(entry.getKey() instanceof String) || entry.getValue() == null) {
                            continue;
                        }
                        String key = (String) entry.getKey();
                        if (!Objects.equals(updated.put(key, entry.getValue()), entry.getValue())) {
                            changed.add(key);
                        }
                    }
                }
                values = immutableCopy(updated);
            }
            notifyListeners(changed);
        }

        private void replaceWithEmpty() {
            Set<String> changed;
            synchronized (this) {
                changed = new HashSet<>(values.keySet());
                values = Collections.emptyMap();
            }
            notifyListeners(changed);
        }

        private void notifyListeners(Set<String> changed) {
            if (changed.isEmpty()) {
                return;
            }
            List<OnSharedPreferenceChangeListener> snapshot =
                    new ArrayList<>(listeners);
            for (String key : changed) {
                for (OnSharedPreferenceChangeListener listener : snapshot) {
                    listener.onSharedPreferenceChanged(this, key);
                }
            }
        }

        private static Map<String, Object> immutableCopy(Map<String, Object> source) {
            Map<String, Object> copy = new HashMap<>();
            source.forEach((key, value) -> {
                if (value instanceof Set<?>) {
                    copy.put(key, new HashSet<>((Set<?>) value));
                } else {
                    copy.put(key, value);
                }
            });
            return Collections.unmodifiableMap(copy);
        }

        private final class RemoteEditor implements Editor {
            private boolean clear;
            private final Set<String> deletes = new HashSet<>();
            private final Map<String, Object> puts = new HashMap<>();

            private Editor put(String key, Object value) {
                if (value == null) {
                    return remove(key);
                }
                deletes.remove(key);
                puts.put(key, value);
                return this;
            }

            @Override
            public Editor putString(String key, String value) {
                return put(key, value);
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                return put(key, value == null ? null : new HashSet<>(value));
            }

            @Override
            public Editor putInt(String key, int value) {
                return put(key, value);
            }

            @Override
            public Editor putLong(String key, long value) {
                return put(key, value);
            }

            @Override
            public Editor putFloat(String key, float value) {
                return put(key, value);
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                return put(key, value);
            }

            @Override
            public Editor remove(String key) {
                puts.remove(key);
                deletes.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                puts.clear();
                deletes.clear();
                return this;
            }

            @Override
            public boolean commit() {
                Bundle diff = buildDiff();
                if (diff == null) {
                    return true;
                }
                try {
                    service.updateRemotePreferences(group, diff);
                    applyDiff(diff);
                    return true;
                } catch (RemoteException exception) {
                    return false;
                }
            }

            @Override
            public void apply() {
                Bundle diff = buildDiff();
                if (diff == null) {
                    return;
                }
                applyDiff(diff);
                EXECUTOR.execute(() -> {
                    try {
                        service.updateRemotePreferences(group, diff);
                    } catch (RemoteException ignored) {
                        // SharedPreferences.apply() cannot report asynchronous persistence errors.
                    }
                });
            }

            private Bundle buildDiff() {
                if (!clear && deletes.isEmpty() && puts.isEmpty()) {
                    return null;
                }
                Bundle diff = new Bundle();
                diff.putBoolean("clear", clear);
                diff.putSerializable("delete", new HashSet<>(deletes));
                diff.putSerializable("put", new HashMap<>(puts));
                return diff;
            }
        }
    }
}
