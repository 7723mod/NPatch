package top.nkbe.npatch.util;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import org.matrix.vector.ipc.IModuleService;

/**
 * Internal bridge used by a scoped target to obtain the Manager-backed read-only remote service.
 */
public final class ManagerRemoteServiceBridge {
    private static final String AUTHORITY = "top.nkbe.npatch.remote";
    private static final String METHOD_GET_INJECTED_SERVICE = "getInjectedRemoteService";
    private static final String KEY_MODULE_PACKAGE = "modulePackageName";
    private static final String KEY_BINDER = "binder";

    private ManagerRemoteServiceBridge() {
    }

    public static IModuleService connect(
            Context context,
            String modulePackageName
    ) {
        Bundle extras = new Bundle();
        extras.putString(KEY_MODULE_PACKAGE, modulePackageName);
        Bundle result = context.getContentResolver().call(
                Uri.parse("content://" + AUTHORITY),
                METHOD_GET_INJECTED_SERVICE,
                null,
                extras
        );
        IBinder binder = result == null ? null : result.getBinder(KEY_BINDER);
        IModuleService service =
                IModuleService.Stub.asInterface(binder);
        if (service == null) {
            throw new IllegalStateException(
                    "NPatch Manager rejected the injected remote service request");
        }
        return service;
    }
}
