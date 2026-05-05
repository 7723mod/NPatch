package top.nkbe.npatch.manager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.launch
import top.nkbe.npatch.lspApp
import nkbe.util.NeoPackageManager

class AppBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AppBroadcastReceiver"

        private val actions = setOf(
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED,
            Intent.ACTION_PACKAGE_REPLACED
        )

        fun register(context: Context) {
            val filter = IntentFilter().apply {
                actions.forEach(::addAction)
                addDataScheme("package")
            }
            context.registerReceiver(AppBroadcastReceiver(), filter)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in actions || intent.action == Intent.ACTION_UID_REMOVED) {
            lspApp.globalScope.launch {
                Log.i(TAG, "Received intent: $intent")
                NeoPackageManager.fetchAppList()
            }
        }
    }
}
