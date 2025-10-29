package org.lsposed.lspatch.service;

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

import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.Closeable;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteApplicationService implements ILSPApplicationService, IBinder.DeathRecipient, Closeable {

    private static final String TAG = "NPatch";
    private static final String MODULE_SERVICE = "org.lsposed.lspatch.manager.ModuleService";

    private volatile ILSPApplicationService mService;
    private final Context mContext;
    private final ServiceConnection mConnection;
    private HandlerThread mHandlerThread;

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) throws RemoteException {
        this.mContext = context.getApplicationContext();

        var intent = new Intent()
                .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE))
                .putExtra("packageName", mContext.getPackageName());
        var latch = new CountDownLatch(1);
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                Log.i(TAG, "Manager binder received");
                mService = ILSPApplicationService.Stub.asInterface(binder);
                try {
                    // 註冊 Binder 死亡通知
                    binder.linkToDeath(RemoteApplicationService.this, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to link to death", e);
                    mService = null;
                }
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.e(TAG, "Manager service died");
                mService = null;
            }
        };

        Log.i(TAG, "Request manager binder");
        mHandlerThread = null; // Initialize member
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mContext.bindService(intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), mConnection);
            } else {
                // 為 ＜29 創建一個臨時的 HandlerThread
                mHandlerThread = new HandlerThread("RemoteApplicationService");
                mHandlerThread.start();
                var handler = new Handler(mHandlerThread.getLooper());
                var contextImplClass = context.getClass();
                var getUserMethod = contextImplClass.getMethod("getUser");
                var bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                        "bindServiceAsUser", Intent.class, ServiceConnection.class, int.class, Handler.class, UserHandle.class);
                var userHandle = (UserHandle) getUserMethod.invoke(context);
                bindServiceAsUserMethod.invoke(context, intent, mConnection, Context.BIND_AUTO_CREATE, handler, userHandle);
            }
            boolean success = latch.await(3, TimeUnit.SECONDS);

            if (!success) {
                // Attempt to unbind the service before throwing a timeout for cleanup
                try {
                    mContext.unbindService(mConnection);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // Ignored
                }
                throw new TimeoutException("Bind service timeout");
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                 InterruptedException | TimeoutException e) {
            var r = new RemoteException("Failed to get manager binder");
            r.initCause(e);
            throw r;
        } finally {
        }
    }

    private ILSPApplicationService getServiceOrThrow() throws RemoteException {
        ILSPApplicationService service = mService;
        if (service == null) {
            throw new RemoteException("Manager service is not connected or has died.");
        }
        return service;
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        return getServiceOrThrow().getLegacyModulesList();
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        return getServiceOrThrow().getModulesList();
    }

    @Override
    public String getPrefsPath(String packageName) throws RemoteException {
        return getServiceOrThrow().getPrefsPath(packageName);
    }

    @Override
    public IBinder asBinder() {
        return mService == null ? null : mService.asBinder();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException {
        // return getServiceOrThrow().requestInjectedManagerBinder(binder);
        return null;
    }

    @Override
    public void binderDied() {
        Log.e(TAG, "Manager service binder has died.");
        if (mService != null) {
            mService.asBinder().unlinkToDeath(this, 0);
        }
        mService = null;
    }

    @Override
    public void close() {
        if (mService != null) {
            mService.asBinder().unlinkToDeath(this, 0);
        }
        try {
            // 解綁服務
            mContext.unbindService(mConnection);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Service was not registered or already unbound: " + e.getMessage());
        }
        
        mService = null;
    }
}