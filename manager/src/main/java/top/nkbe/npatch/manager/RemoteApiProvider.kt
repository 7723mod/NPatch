package top.nkbe.npatch.manager

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.util.LocalInjectedModuleService

/**
 * Authenticated bridge for LoadedModule applications and manager-backed injected targets.
 *
 * LoadedModule UIDs receive the standard writable IXposedService. Scoped target UIDs can only request
 * Vector's read-only IModuleService, preserving the API 101/102 privilege boundary.
 */
class RemoteApiProvider : ContentProvider() {
    companion object {
        const val METHOD_GET_REMOTE_SERVICE = "getRemoteService"
        const val METHOD_GET_INJECTED_SERVICE = "getInjectedRemoteService"
        const val KEY_MODULE_PACKAGE = "modulePackageName"
        const val KEY_BINDER = "binder"
        const val KEY_CLIENT_TOKEN = "clientToken"
        private const val TAG = "NPatchRemoteApi"

        private val xposedServices = ConcurrentHashMap<Pair<String, Int>, XposedServiceBinder>()
        private val injectedServices = ConcurrentHashMap<Pair<String, Int>, LocalInjectedModuleService>()
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_GET_REMOTE_SERVICE && method != METHOD_GET_INJECTED_SERVICE) {
            return super.call(method, arg, extras)
        }
        val providerContext = context ?: return null
        val modulePackageName = extras?.getString(KEY_MODULE_PACKAGE) ?: return null
        val clientToken = extras.getBinder(KEY_CLIENT_TOKEN)
        val callingUid = Binder.getCallingUid()
        val cacheKey = Pair(modulePackageName, callingUid)

        if (clientToken != null) {
            try {
                clientToken.linkToDeath({
                    if (method == METHOD_GET_REMOTE_SERVICE) {
                        xposedServices.remove(cacheKey)
                    } else {
                        injectedServices.remove(cacheKey)
                    }
                }, 0)
            } catch (e: Exception) {
                Log.d(TAG, "Failed to link client token to death for $cacheKey", e)
            }
        }

        if (method == METHOD_GET_REMOTE_SERVICE) {
            xposedServices[cacheKey]?.let { cached ->
                val binder = cached.asBinder()
                if (binder.isBinderAlive) {
                    return Bundle().apply { putBinder(KEY_BINDER, binder) }
                } else {
                    xposedServices.remove(cacheKey, cached)
                }
            }
        } else {
            injectedServices[cacheKey]?.let { cached ->
                val binder = cached.asBinder()
                if (binder.isBinderAlive) {
                    return Bundle().apply { putBinder(KEY_BINDER, binder) }
                } else {
                    injectedServices.remove(cacheKey, cached)
                }
            }
        }

        val callerPackages = providerContext.packageManager.getPackagesForUid(callingUid).orEmpty()

        val isKnownModule =
            runCatching {
                runBlocking { ConfigManager.isModuleConfigured(modulePackageName) }
            }.getOrDefault(false)
        if (!isKnownModule) {
            Log.w(TAG, "Rejected unknown LoadedModule $modulePackageName from uid=$callingUid")
            return null
        }

        val service: IBinder =
            when (method) {
                METHOD_GET_REMOTE_SERVICE -> {
                    if (modulePackageName !in callerPackages) {
                        Log.w(TAG, "Rejected writable service for uid=$callingUid")
                        return null
                    }
                    val created = xposedServices.computeIfAbsent(cacheKey) {
                        XposedServiceBinder(modulePackageName, callingUid)
                    }
                    created.asBinder()
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
                    val created = injectedServices.computeIfAbsent(cacheKey) {
                        LocalInjectedModuleService(providerContext, modulePackageName, callingUid)
                    }
                    created.asBinder()
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
