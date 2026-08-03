package top.nkbe.npatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import top.nkbe.npatch.share.Constants;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.IHotReloadTarget;
import org.lsposed.lspd.service.ILSPInjectedModuleService;
import org.lsposed.lspd.service.ILSPApplicationService;
import org.lsposed.lspd.service.IRemotePreferenceCallback;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class RemoteApplicationService implements ILSPApplicationService {

    private static final String TAG = "NPatch";
    private static final String MODULE_SERVICE = "top.nkbe.npatch.manager.ModuleService";
    private static final int CONNECTION_TIMEOUT_SEC = 2;
    private static final int MAX_BIND_ATTEMPTS = 2;
    private static final int REGISTER_CLIENT_PACKAGE = 0x4E5041;
    private static final ExecutorService BIND_EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "NPatch-ManagerBind");
                thread.setDaemon(true);
                return thread;
            });

    private final Context context;
    private final AtomicBoolean managerAvailable = new AtomicBoolean(true);
    private volatile List<Module> cachedLegacyModuleScope = Collections.emptyList();
    private volatile List<Module> cachedModuleScope = Collections.emptyList();
    private volatile ILSPApplicationService service;
    private volatile ServiceConnection connection;

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) throws RemoteException {
        Context appContext = context.getApplicationContext();
        this.context = appContext == null ? context : appContext;
        try {
            Intent intent = new Intent()
                    .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE))
                    .putExtra("packageName", this.context.getPackageName());

            Throwable lastError = null;
            for (int attempt = 1; attempt <= MAX_BIND_ATTEMPTS && service == null; attempt++) {
                Log.i(TAG, "Requesting manager binder... attempt " + attempt + "/" + MAX_BIND_ATTEMPTS);
                try {
                    service = bindOnce(intent);
                } catch (Throwable error) {
                    lastError = error;
                    Log.w(TAG, "Manager bind attempt failed", error);
                }
            }

            if (service == null) {
                RemoteException failure =
                        new RemoteException("Failed to get manager binder after "
                                + MAX_BIND_ATTEMPTS + " attempts");
                if (lastError != null) {
                    failure.initCause(lastError);
                }
                throw failure;
            }
        } catch (Throwable e) {
            if (e instanceof RemoteException) {
                throw (RemoteException) e;
            }
            RemoteException remoteException = new RemoteException("Failed to get manager binder");
            remoteException.initCause(e);
            throw remoteException;
        }
    }

    private ILSPApplicationService bindOnce(Intent intent) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        AtomicReference<ILSPApplicationService> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        ServiceConnection candidate = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (cancelled.get()) {
                    safeUnbind(this);
                    return;
                }
                try {
                    registerClientPackage(binder, context.getPackageName());
                    ILSPApplicationService connected = Stub.asInterface(binder);
                    if (connected == null || !binder.isBinderAlive()) {
                        throw new RemoteException("Manager returned a dead binder");
                    }
                    managerAvailable.set(true);
                    result.set(connected);
                    Log.i(TAG, "Manager binder received and caller identity registered");
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                disconnected.set(true);
                managerAvailable.set(false);
                if (connection == this) {
                    Log.e(TAG, "Manager service disconnected");
                    service = null;
                    connection = null;
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                managerAvailable.set(false);
                failure.compareAndSet(null, new RemoteException("Manager binding died"));
                onServiceDisconnected(name);
                latch.countDown();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                disconnected.set(true);
                managerAvailable.set(false);
                failure.compareAndSet(null, new RemoteException("Manager returned a null binding"));
                latch.countDown();
            }
        };

        boolean bindStarted = bindServiceCompat(intent, candidate);
        if (!bindStarted) {
            throw new RemoteException("bindService returned false");
        }

        if (!latch.await(CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            cancelled.set(true);
            safeUnbind(candidate);
            throw new TimeoutException("Manager bind timed out");
        }

        Throwable error = failure.get();
        ILSPApplicationService connected = result.get();
        if (error != null
                || disconnected.get()
                || connected == null
                || !connected.asBinder().isBinderAlive()) {
            cancelled.set(true);
            safeUnbind(candidate);
            if (error instanceof Exception) {
                throw (Exception) error;
            }
            throw new RemoteException("Manager bind failed");
        }
        connection = candidate;
        if (disconnected.get() || !connected.asBinder().isBinderAlive()) {
            connection = null;
            cancelled.set(true);
            safeUnbind(candidate);
            throw new RemoteException("Manager disconnected while completing bind");
        }
        return connected;
    }

    @SuppressLint("DiscouragedPrivateApi")
    private boolean bindServiceCompat(Intent intent, ServiceConnection candidate)
            throws ReflectiveOperationException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return context.bindService(
                    intent,
                    Context.BIND_AUTO_CREATE,
                    BIND_EXECUTOR,
                    candidate
            );
        }

        Class<?> contextImplClass = context.getClass();
        Method getUserMethod = contextImplClass.getMethod("getUser");
        UserHandle userHandle = (UserHandle) getUserMethod.invoke(context);
        Method bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                "bindServiceAsUser",
                Intent.class,
                ServiceConnection.class,
                int.class,
                Handler.class,
                UserHandle.class
        );
        Object result = bindServiceAsUserMethod.invoke(
                context,
                intent,
                candidate,
                Context.BIND_AUTO_CREATE,
                new Handler(Looper.getMainLooper()),
                userHandle
        );
        return !(result instanceof Boolean) || (Boolean) result;
    }

    private void safeUnbind(ServiceConnection candidate) {
        try {
            context.unbindService(candidate);
        } catch (IllegalArgumentException ignored) {
            // The framework may dispatch a late callback after an already completed unbind.
        }
    }

    private static void registerClientPackage(IBinder binder, String packageName)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("org.lsposed.lspd.service.ILSPApplicationService");
            data.writeString(packageName);
            if (!binder.transact(REGISTER_CLIENT_PACKAGE, data, reply, 0)) {
                throw new RemoteException("Manager does not support caller registration");
            }
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        ILSPApplicationService current = service;
        if (current == null || !managerAvailable.get()) {
            return getCachedModuleScope(true);
        }
        try {
            return cacheModuleScope(true, current.getLegacyModulesList());
        } catch (RemoteException error) {
            onManagerFailure("get legacy modules", error);
            return getCachedModuleScope(true);
        }
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        ILSPApplicationService current = service;
        if (current == null || !managerAvailable.get()) {
            return getCachedModuleScope(false);
        }
        try {
            return cacheModuleScope(false, current.getModulesList());
        } catch (RemoteException error) {
            onManagerFailure("get modules", error);
            return getCachedModuleScope(false);
        }
    }

    private List<Module> cacheModuleScope(boolean legacy, List<Module> modules) {
        List<Module> protectedModules = protectModules(modules);
        List<Module> snapshot = protectedModules == null || protectedModules.isEmpty()
                ? Collections.emptyList()
                : new ArrayList<>(protectedModules);
        if (legacy) {
            cachedLegacyModuleScope = snapshot;
        } else {
            cachedModuleScope = snapshot;
        }
        return new ArrayList<>(snapshot);
    }

    private List<Module> getCachedModuleScope(boolean legacy) {
        return new ArrayList<>(legacy ? cachedLegacyModuleScope : cachedModuleScope);
    }

    private List<Module> protectModules(List<Module> modules) {
        if (modules == null || modules.isEmpty()) {
            return modules;
        }
        for (Module module : modules) {
            if (module == null || module.service == null
                    || module.service instanceof DisconnectSafeInjectedModuleService) {
                continue;
            }
            module.service = new DisconnectSafeInjectedModuleService(
                    module.service, module.packageName, managerAvailable);
        }
        return modules;
    }

    @Override
    public String getPrefsPath(String packageName) {
        ILSPApplicationService current = service;
        if (current == null || !managerAvailable.get()) {
            return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/")
                    .getAbsolutePath();
        }
        try {
            return current.getPrefsPath(packageName);
        } catch (RemoteException e) {
            onManagerFailure("get preferences path", e);
            return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/")
                    .getAbsolutePath();
        }
    }

    @Override
    public IBinder asBinder() {
        return service == null ? null : service.asBinder();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) {
        ILSPApplicationService current = service;
        if (current == null || !managerAvailable.get()) {
            return null;
        }
        try {
            return current.requestInjectedManagerBinder(binder);
        } catch (RemoteException e) {
            onManagerFailure("request injected manager binder", e);
            return null;
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    @Override
    public void registerHotReloadTarget(IHotReloadTarget target) throws RemoteException {
        ILSPApplicationService current = service;
        if (current != null && managerAvailable.get()) {
            try {
                current.registerHotReloadTarget(target);
            } catch (RemoteException error) {
                onManagerFailure("register hot reload target", error);
            }
        }
    }

    private void onManagerFailure(String operation, RemoteException error) {
        managerAvailable.set(false);
        Log.w(TAG, "Manager unavailable while attempting to " + operation
                + "; using local fallback", error);
    }

    /**
     * Module metadata crosses the manager boundary together with a remote preference/file
     * service. That Binder belongs to the manager process, so keeping it after the manager dies
     * makes a later module callback throw DeadObjectException in the target process. The module
     * remains in its locally cached scope; only remote data calls degrade to empty read-only
     * results instead of allowing manager lifecycle to terminate the host app.
     */
    private static final class DisconnectSafeInjectedModuleService
            extends ILSPInjectedModuleService.Stub {
        private final ILSPInjectedModuleService delegate;
        private final String modulePackageName;
        private final AtomicBoolean managerAvailable;

        DisconnectSafeInjectedModuleService(
                ILSPInjectedModuleService delegate,
                String modulePackageName,
                AtomicBoolean managerAvailable
        ) {
            this.delegate = delegate;
            this.modulePackageName = modulePackageName;
            this.managerAvailable = managerAvailable;
        }

        @Override
        public long getFrameworkProperties() {
            if (!managerAvailable.get()) {
                return 0L;
            }
            try {
                return delegate.getFrameworkProperties();
            } catch (RemoteException error) {
                onManagerFailure(error);
                return 0L;
            }
        }

        @Override
        public Bundle requestRemotePreferences(
                String group,
                IRemotePreferenceCallback callback
        ) {
            if (!managerAvailable.get()) {
                return Bundle.EMPTY;
            }
            try {
                Bundle result = delegate.requestRemotePreferences(group, callback);
                return result == null ? Bundle.EMPTY : result;
            } catch (RemoteException error) {
                onManagerFailure(error);
                return Bundle.EMPTY;
            }
        }

        @Override
        public ParcelFileDescriptor openRemoteFile(String path) {
            if (!managerAvailable.get()) {
                return null;
            }
            try {
                return delegate.openRemoteFile(path);
            } catch (RemoteException error) {
                onManagerFailure(error);
                return null;
            }
        }

        @Override
        public String[] getRemoteFileList() {
            if (!managerAvailable.get()) {
                return new String[0];
            }
            try {
                String[] result = delegate.getRemoteFileList();
                return result == null ? new String[0] : result;
            } catch (RemoteException error) {
                onManagerFailure(error);
                return new String[0];
            }
        }

        private void onManagerFailure(RemoteException error) {
            managerAvailable.set(false);
            Log.w(TAG, "Manager-backed service unavailable for " + modulePackageName
                    + "; using empty read-only service", error);
        }
    }
}
