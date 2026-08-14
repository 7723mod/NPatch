package top.nkbe.npatch.manager

import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.HookedProcess
import io.github.libxposed.service.IHotReloadCallback
import io.github.libxposed.service.IXposedScopeCallback
import io.github.libxposed.service.IXposedService
import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.util.NPatchRemoteStore

class XposedServiceBinder(
    private val packageName: String,
    private val allowedUid: Int? = null,
) : IXposedService.Stub() {

    private val remoteStore by lazy { NPatchRemoteStore.get(lspApp, packageName) }

    override fun getApiVersion(): Int {
        enforceCaller()
        return IXposedService.LIB_API
    }

    override fun getFrameworkName(): String {
        enforceCaller()
        return "NPatch"
    }

    override fun getFrameworkVersion(): String {
        enforceCaller()
        return BuildConfig.VERSION_NAME
    }

    override fun getFrameworkVersionCode(): Long {
        enforceCaller()
        return BuildConfig.VERSION_CODE.toLong()
    }

    override fun getFrameworkProperties(): Long {
        enforceCaller()
        return NPatchRemoteStore.CAP_REMOTE
    }

    override fun getScope(): List<String> {
        enforceCaller()
        return runBlocking { ConfigManager.getAppsForModule(packageName) }
    }

    override fun requestScope(packages: List<String>, callback: IXposedScopeCallback) {
        enforceCaller()
        val requested = packages.distinct().sorted()
        if (requested.isEmpty()) {
            callback.onScopeRequestApproved(emptyList())
            return
        }
        runBlocking {
            requested.forEach { appPkg ->
                ConfigManager.activateModule(
                    appPkg,
                    top.nkbe.npatch.database.entity.LoadedModule(packageName, ""),
                )
            }
            callback.onScopeRequestApproved(requested)
        }
    }

    override fun removeScope(packages: List<String>) {
        enforceCaller()
        val requested = packages.distinct()
        if (requested.isEmpty()) return
        runBlocking {
            requested.forEach { appPkg ->
                ConfigManager.deactivateModule(
                    appPkg,
                    top.nkbe.npatch.database.entity.LoadedModule(packageName, ""),
                )
            }
        }
    }

    override fun getRunningTargets(): List<HookedProcess> {
        enforceCaller()
        return HotReloadRegistry.getRunningTargets(packageName)
    }

    override fun hotReloadModule(
        targetId: Long,
        data: Bundle?,
        callback: IHotReloadCallback?,
    ) {
        enforceCaller()
        HotReloadRegistry.hotReload(packageName, targetId, data, callback)
    }

    override fun requestRemotePreferences(group: String): Bundle {
        enforceCaller()
        return remoteStore.requestPreferences(group, null)
    }

    override fun updateRemotePreferences(group: String, diff: Bundle) {
        enforceCaller()
        remoteStore.updatePreferences(group, diff)
    }

    override fun deleteRemotePreferences(group: String) {
        enforceCaller()
        remoteStore.deletePreferences(group)
    }

    override fun listRemoteFiles(): Array<String> {
        enforceCaller()
        return remoteStore.listFiles()
    }

    override fun openRemoteFile(name: String): ParcelFileDescriptor {
        enforceCaller()
        return remoteStore.openFile(name, true)
    }

    override fun deleteRemoteFile(name: String): Boolean {
        enforceCaller()
        return remoteStore.deleteFile(name)
    }

    private fun enforceCaller() {
        val expectedUid = allowedUid ?: return
        if (Binder.getCallingUid() != expectedUid) {
            throw SecurityException("Xposed service binder was passed to another UID")
        }
    }
}
