package top.nkbe.npatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import top.nkbe.npatch.share.Constants;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteApplicationService implements ILSPApplicationService {

    private static final String TAG = "NPatch";
    private static final String MODULE_SERVICE = "top.nkbe.npatch.manager.ModuleService";
    private static final int CONNECTION_TIMEOUT_SEC = 2;
    private static final int MAX_BIND_ATTEMPTS = 1;

    private volatile ILSPApplicationService service;

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) throws RemoteException {
        try {
            Intent intent = new Intent()
                    .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE))
                    .putExtra("packageName", context.getPackageName());

            Throwable lastError = null;
            for (int attempt = 1; attempt <= MAX_BIND_ATTEMPTS && service == null; attempt++) {
                CountDownLatch latch = new CountDownLatch(1);
                ServiceConnection conn = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder binder) {
                        Log.i(TAG, "Manager binder received");
                        service = Stub.asInterface(binder);
                        latch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.e(TAG, "Manager service died");
                        service = null;
                    }
                };

                Log.i(TAG, "Requesting manager binder... attempt " + attempt + "/" + MAX_BIND_ATTEMPTS);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        boolean bindOk = context.bindService(
                                intent,
                                Context.BIND_AUTO_CREATE,
                                Executors.newSingleThreadExecutor(),
                                conn
                        );
                        if (!bindOk) {
                            throw new RemoteException("bindService returned false");
                        }
                    } else {
                        HandlerThread handlerThread = new HandlerThread("RemoteApplicationService");
                        handlerThread.start();
                        Handler handler = new Handler(handlerThread.getLooper());

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

                        bindServiceAsUserMethod.invoke(context, intent, conn, Context.BIND_AUTO_CREATE, handler, userHandle);
                    }

                    boolean success = latch.await(CONNECTION_TIMEOUT_SEC, TimeUnit.SECONDS);
                    if (!success) {
                        throw new TimeoutException("Bind service timeout");
                    }
                } catch (Exception e) {
                    lastError = e;
                    Log.w(TAG, "Manager bind attempt failed", e);
                }
            }

            if (service == null) {
                if (lastError != null) {
                    if (lastError instanceof RemoteException) {
                        throw (RemoteException) lastError;
                    }
                    RemoteException remoteException = new RemoteException("Failed to get manager binder");
                    remoteException.initCause(lastError);
                    throw remoteException;
                }
                throw new TimeoutException("Bind service failed after retries");
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
}
