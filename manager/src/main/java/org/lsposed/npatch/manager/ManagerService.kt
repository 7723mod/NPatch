package org.lsposed.npatch.manager

import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.lsposed.npatch.config.ConfigManager
import org.lsposed.npatch.lspApp
import org.lsposed.lspd.models.Module
import org.lsposed.lspd.service.ILSPApplicationService

object ManagerService : ILSPApplicationService.Stub() {

    private const val TAG = "ManagerService"

    private fun getCallingPackageName(): String? {
        return lspApp.packageManager.getNameForUid(Binder.getCallingUid())
    }

    override fun isLogMuted(): Boolean {
        return false
    }

    override fun getLegacyModulesList(): List<Module> {
        val app = getCallingPackageName()
        val list = app?.let {
            runBlocking { ConfigManager.getModuleFilesForApp(it) }
        }.orEmpty().filter { it.file?.legacy == true }
        Log.d(TAG, "$app calls getLegacyModulesList: $list")
        return list
    }

    override fun getModulesList(): List<Module> {
        val app = getCallingPackageName()
        val list = app?.let {
            runBlocking { ConfigManager.getModuleFilesForApp(it) }
        }.orEmpty().filter { it.file?.legacy == false }
        Log.d(TAG, "$app calls getModulesList: $list")
        return list
    }

    override fun getPrefsPath(packageName: String): String {
        val userId = Binder.getCallingUid() / 100000
        return if (userId == 0) {
            "/data/data/$packageName/shared_prefs/"
        } else {
            "/data/user/$userId/$packageName/shared_prefs/"
        }
    }

    override fun requestInjectedManagerBinder(binder: MutableList<IBinder>): ParcelFileDescriptor? {
        return null
    }
}
