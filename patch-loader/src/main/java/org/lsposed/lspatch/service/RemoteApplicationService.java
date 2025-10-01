package org.lsposed.lspatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;

import org.lsposed.lspatch.share.Constants;
import org.lsposed.lspd.models.Module;
import org.lsposed.lspd.service.ILSPApplicationService;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteApplicationService implements ILSPApplicationService {

    private static final String TAG = "NPatch";
    private static final String MODULE_SERVICE = "org.lsposed.lspatch.manager.ModuleService";
    private static final long BIND_TIMEOUT_SECONDS = 1;

    private volatile ILSPApplicationService service;

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) throws RemoteException {
        var intent = new Intent()
                .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE))
                .putExtra("packageName", context.getPackageName());

        var latch = new CountDownLatch(1);
        var conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                Log.i(TAG, "Manager binder received");
                service = ILSPApplicationService.Stub.asInterface(binder);
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.e(TAG, "Manager service died");
                service = null;
            }
        };

        Log.i(TAG, "Request manager binder");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.bindService(intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), conn);
            } else {
                var contextImplClass = context.getClass();
                var getUserMethod = contextImplClass.getMethod("getUser");
                var bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                        "bindServiceAsUser", Intent.class, ServiceConnection.class, int.class, Handler.class, UserHandle.class);
                var userHandle = (UserHandle) getUserMethod.invoke(context);

                // Android Q 以下使用反射调用 bindServiceAsUser，handler 设为 null
                bindServiceAsUserMethod.invoke(context, intent, conn, Context.BIND_AUTO_CREATE, null, userHandle);
            }

            boolean success = latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!success) {
                throw new TimeoutException("Bind service timeout after " + BIND_TIMEOUT_SECONDS + " seconds");
            }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                 InterruptedException | TimeoutException e) {
            var r = new RemoteException("Failed to get manager binder");
            r.initCause(e);
            throw r;
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return false;
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        var result = Objects.requireNonNullElse(service, new EmptyApplicationService()).getLegacyModulesList();
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        var result = Objects.requireNonNullElse(service, new EmptyApplicationService()).getModulesList();
        return Collections.unmodifiableList(result);
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data" + File.separator + packageName + File.separator + "shared_prefs" + File.separator).getAbsolutePath();
    }

    @Override
    public IBinder asBinder() {
        return service == null ? null : service.asBinder();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) {
        return null;
    }

    // 处理 service == null 的情况
    private static class EmptyApplicationService extends ILSPApplicationService.Stub {
        @Override
        public List<Module> getLegacyModulesList() {
            return new ArrayList<>();
        }
        @Override
        public List<Module> getModulesList() {
            return new ArrayList<>();
        }
        @Override
        public String getPrefsPath(String packageName) {
            return new File(Environment.getDataDirectory(), "data" + File.separator + packageName + File.separator + "shared_prefs" + File.separator).getAbsolutePath();
        }
        @Override
        public IBinder asBinder() {
            return null;
        }
        @Override
        public boolean isLogMuted() {
            return false;
        }
        @Override
        public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) {
            return null;
        }
    }
}
