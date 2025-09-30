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

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class RemoteApplicationService implements ILSPApplicationService {

    private static final String TAG = "NPatch";
    private static final String MODULE_SERVICE_COMPONENT = "org.lsposed.lspatch.manager.ModuleService";
    private static final long BIND_TIMEOUT_SECONDS = 1;

    private volatile ILSPApplicationService service;

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static void bindServicePreQ(Context context, Intent intent,
                                        ServiceConnection conn, Handler handler) throws Exception {
        final Class<? extends Context> contextImplClass = context.getClass();

        final Object userHandle = contextImplClass.getMethod("getUser").invoke(context);

        // bindServiceAsUser(Intent intent, ServiceConnection conn, int flags, Handler handler, UserHandle user)
        final java.lang.reflect.Method bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                "bindServiceAsUser", Intent.class, ServiceConnection.class, int.class, Handler.class, UserHandle.class);

        bindServiceAsUserMethod.invoke(context, intent, conn, Context.BIND_AUTO_CREATE, handler, (UserHandle) userHandle);
    }

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context) throws RemoteException {
        final Intent intent = new Intent()
                .setComponent(new ComponentName(Constants.MANAGER_PACKAGE_NAME, MODULE_SERVICE_COMPONENT))
                .putExtra("packageName", context.getPackageName());

        final CountDownLatch latch = new CountDownLatch(1);
        final ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                Log.i(TAG, "Manager binder received");
                service = ILSPApplicationService.Stub.asInterface(binder);
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.e(TAG, "Manager service died");
                service = null; // 服務斷開連接時清除引用
            }
        };

        Log.i(TAG, "Request manager binder");

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ 使用官方支持的帶 Executor 的 bindService
                context.bindService(intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), conn);
            } else {
                // API<29 使用反射調用 bindServiceAsUser
                final HandlerThread handlerThread = new HandlerThread("RemoteApplicationServiceBinder");
                handlerThread.start();
                final Handler handler = new Handler(handlerThread.getLooper());

                bindServicePreQ(context, intent, conn, handler);
            }

            boolean success = latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!success) {
                throw new RemoteException("Bind service timeout after " + BIND_TIMEOUT_SECONDS + " seconds");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteException("Service binding interrupted: " + e.getMessage());
        } catch (TimeoutException e) {
            // 绑定超时
            throw new RemoteException(e.getMessage());
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // API<29 可能反射失敗
            final RemoteException r = new RemoteException("Failed to get manager binder via reflection");
            r.initCause(e);
            throw r;
        } catch (Exception e) {
            final RemoteException r = new RemoteException("Failed to bind manager service");
            r.initCause(e);
            throw r;
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        if (service == null) {
            Log.w(TAG, "isLogMuted called but service is null. Returning default false.");
            return false;
        }
        return service.isLogMuted();
    }

    @Override
    public List<Module> getLegacyModulesList() throws RemoteException {
        if (service == null) {
            Log.w(TAG, "getLegacyModulesList called but service is null. Returning empty list.");
            return new ArrayList<>();
        }
        return service.getLegacyModulesList();
    }

    @Override
    public List<Module> getModulesList() throws RemoteException {
        if (service == null) {
            Log.w(TAG, "getModulesList called but service is null. Returning empty list.");
            return new ArrayList<>();
        }
        return service.getModulesList();
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data" + File.separator + packageName + File.separator + "shared_prefs").getAbsolutePath();
    }

    @Override
    public IBinder asBinder() {
        return service == null ? null : service.asBinder();
    }

    @Override
    public ParcelFileDescriptor requestInjectedManagerBinder(List<IBinder> binder) throws RemoteException {
        // 如果 service 為 null，則直接返回，避免丟出 NullPointerException
        if (service == null) {
            Log.w(TAG, "requestInjectedManagerBinder called but service is null. Returning null.");
            return null;
        }
        return service.requestInjectedManagerBinder(binder);
    }
}
