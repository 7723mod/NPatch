package org.lsposed.npatch.manager

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log


class ModuleService : Service() {

    companion object {
        private const val TAG = "ModuleService"
    }

    private fun isTrustedCaller(packageName: String): Boolean {
        val callingUid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(callingUid).orEmpty()
        return packages.contains(packageName)
    }

    override fun onBind(intent: Intent): IBinder? {
        val packageName = intent.getStringExtra("packageName") ?: return null

        if (!isTrustedCaller(packageName)) {
            Log.w(TAG, "Rejected binder request from uid=${Binder.getCallingUid()} for $packageName")
            return null
        }

        Log.i(TAG, "$packageName requests binder")
        return ManagerService.asBinder()
    }
}
