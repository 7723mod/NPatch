package top.nkbe.npatch.service;

import android.os.Bundle;
import android.util.Log;

import org.matrix.vector.impl.core.VectorModuleManager;
import org.matrix.vector.ipc.HotReloadOutcome;
import org.matrix.vector.ipc.IHotReloadOutcomeReceiver;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.LoadedModule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The host's inbound hot-reload channel for NPatch's manager.
 *
 * <p>Executes in-process generation swap ({@code VectorModuleManager.hotReload}) on behalf of
 * the NPatch manager on a background worker thread and reports the outcome out-of-band.</p>
 */
public class NPatchProcessChannel extends IProcessChannel.Stub {

    private static final String TAG = "NPatch-HotReload";

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "npatch-hot-reload-host");
                t.setDaemon(true);
                return t;
            });

    @Override
    public void hotReload(String modulePackageName, Bundle extras, LoadedModule module,
            IHotReloadOutcomeReceiver receiver) {
        worker.execute(() -> {
            HotReloadOutcome outcome = null;
            try {
                outcome = VectorModuleManager.INSTANCE.hotReload(modulePackageName, extras, module);
            } catch (Throwable t) {
                Log.e(TAG, "Hot reload swap of " + modulePackageName + " failed", t);
            }
            try {
                if (receiver != null) receiver.onOutcome(outcome);
            } catch (Throwable t) {
                Log.w(TAG, "Cannot report the hot reload outcome", t);
            }
        });
    }
}
