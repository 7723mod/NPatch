package top.nkbe.npatch.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.util.LocalInjectedModuleService

/**
 * Authenticated bridge for module applications and manager-backed injected targets.
 *
 * Module UIDs receive the standard writable IXposedService. Scoped target UIDs can only request
 * Vector's read-only ILSPInjectedModuleService, preserving the API 101/102 privilege boundary.
 */
class RemoteApiProvider : ContentProvider() {
    companion object {
        const val METHOD_GET_REMOTE_SERVICE = "getRemoteService"
        const val METHOD_GET_INJECTED_SERVICE = "getInjectedRemoteService"
        const val KEY_MODULE_PACKAGE = "modulePackageName"
        const val KEY_BINDER = "binder"
        private const val TAG = "NPatchRemoteApi"
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_REMOTE_SERVICE && method != METHOD_GET_INJECTED_SERVICE) {
            return super.call(method, arg, extras)
        }
        val providerContext = context ?: return null
        val modulePackageName = extras?.getString(KEY_MODULE_PACKAGE) ?: return null
        val callingUid = Binder.getCallingUid()
        val callerPackages = providerContext.packageManager.getPackagesForUid(callingUid).orEmpty()

        val isKnownModule =
            runCatching {
                runBlocking { ConfigManager.getModuleFile(modulePackageName) != null }
            }.getOrDefault(false)
        if (!isKnownModule) {
            Log.w(TAG, "Rejected unknown module $modulePackageName from uid=$callingUid")
            return null
        }

        val service =
            when (method) {
                METHOD_GET_REMOTE_SERVICE -> {
                    if (modulePackageName !in callerPackages) {
                        Log.w(TAG, "Rejected writable service for uid=$callingUid")
                        return null
                    }
                    XposedServiceBinder(modulePackageName, callingUid).asBinder()
                }

                else -> {
                    val scopedTargets =
                        runCatching {
                            runBlocking { ConfigManager.getAppsForModule(modulePackageName) }
                        }.getOrDefault(emptyList())
                    if (callerPackages.none(scopedTargets::contains)) {
                        Log.w(TAG, "Rejected injected service for uid=$callingUid")
                        return null
                    }
                    LocalInjectedModuleService(
                        providerContext,
                        modulePackageName,
                        callingUid,
                    ).asBinder()
                }
            }

        return Bundle().apply { putBinder(KEY_BINDER, service) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
