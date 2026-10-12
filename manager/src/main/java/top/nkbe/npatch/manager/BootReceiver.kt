package top.nkbe.npatch.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.service.KeepAliveService

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.i(TAG, "Received $action")
            if (Configs.keepAlive) {
                KeepAliveService.start(context)
            }
        }
    }
}
