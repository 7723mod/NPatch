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

    override fun isLogMuted(): Boolean {
        return false
    }

    override fun getLegacyModulesList(): List<Module> {
        val app = lspApp.packageManager.getNameForUid(Binder.getCallingUid())
        val list = app?.let {
            runBlocking { ConfigManager.getModuleFilesForApp(it) }
        }.orEmpty()
        Log.d(TAG, "$app calls getLegacyModulesList: $list")
        return list
    }

    override fun getModulesList(): List<Module> {
        return emptyList()
    }

    override fun getPrefsPath(packageName: String): String {
        TODO("Not yet implemented")
    }

    override fun requestInjectedManagerBinder(binder: List<IBinder>?): ParcelFileDescriptor? {
        return null
    }
}
