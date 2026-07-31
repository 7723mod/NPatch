package top.nkbe.npatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
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
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
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
                if (connection == this) {
                    Log.e(TAG, "Manager service disconnected");
                    service = null;
                    connection = null;
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                failure.compareAndSet(null, new RemoteException("Manager binding died"));
                onServiceDisconnected(name);
                latch.countDown();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                disconnected.set(true);
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
        return service == null ? new ArrayList<>() : service.getLegacyModulesList();
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return service == null ? new ArrayList<>() : service.getModulesList();
    }

    @Override
    public String getPrefsPath(String packageName) {
        if (service == null) {
            return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/")
                    .getAbsolutePath();
        }
        try {
            return service.getPrefsPath(packageName);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get prefs path from manager", e);
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
        if (service == null) {
            return null;
        }
        try {
            return service.requestInjectedManagerBinder(binder);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to request injected manager binder", e);
            return null;
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    @Override
    public void registerHotReloadTarget(IHotReloadTarget target) throws RemoteException {
        if (service != null) {
            service.registerHotReloadTarget(target);
        }
    }
}
