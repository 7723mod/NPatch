package top.nkbe.npatch.manager

import android.app.IActivityManager
import android.content.AttributionSource
import android.os.Build
import android.os.Bundle
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.util.Log
import io.github.libxposed.service.IXposedService
import java.lang.reflect.Method
import nkbe.util.ShizukuApi
import top.nkbe.npatch.lspApp

object ModuleActivationController {
    private const val TAG = "ModuleActivationController"

    fun activate(packageName: String): Result<Unit> {
        return runCatching {
            ShizukuApi.refreshState()
            check(ShizukuApi.isReady) { "Shizuku is not connected" }

            val authority = packageName + IXposedService.AUTHORITY_SUFFIX
            Log.i(TAG, "Activating module display for $packageName via $authority")
            val activityManager = IActivityManager.Stub.asInterface(ShizukuApi.getSystemService("activity"))
            val providerToken = Binder()
            val providerHolder = activityManager.getContentProviderExternalCompat(authority, providerToken)
            val provider = providerHolder.providerOrThrow(authority)

            try {
                provider.callXposedProvider(authority, XposedServiceBinder(packageName).asBinder())
                Log.i(TAG, "Activation binder delivered to $packageName")
            } finally {
                activityManager.removeContentProviderExternalCompat(authority, providerToken)
            }
            Unit
        }.onFailure {
            Log.w(TAG, "Failed to activate module display for $packageName", it)
        }
    }

    private fun IActivityManager.getContentProviderExternalCompat(authority: String, token: IBinder): Any {
        val method = javaClass.methods.firstOrNull {
            it.name == "getContentProviderExternal" && it.parameterTypes.size == 4
        } ?: javaClass.methods.firstOrNull {
            it.name == "getContentProviderExternal" && it.parameterTypes.size == 3
        } ?: error("IActivityManager.getContentProviderExternal is unavailable")
        val args = if (method.parameterTypes.size == 4) {
            arrayOf(authority, currentUserId(), token, TAG)
        } else {
            arrayOf(authority, currentUserId(), token)
        }
        return method.invoke(this, *args)
            ?: error("Provider $authority is unavailable")
    }

    private fun IActivityManager.removeContentProviderExternalCompat(authority: String, token: IBinder) {
        runCatching {
            val method = javaClass.methods.firstOrNull {
                it.name == "removeContentProviderExternal" && it.parameterTypes.size == 2
            } ?: return
            method.invoke(this, authority, token)
        }.onFailure {
            Log.w(TAG, "Failed to release provider $authority", it)
        }
    }

    private fun Any.providerOrThrow(authority: String): Any {
        val field = javaClass.fields.firstOrNull { it.name == "provider" }
            ?: javaClass.declaredFields.firstOrNull { it.name == "provider" }
            ?: error("Provider holder for $authority has no provider field")
        field.isAccessible = true
        return field.get(this) ?: error("Provider $authority has no binder")
    }

    private fun Any.callXposedProvider(authority: String, binder: IBinder) {
        val extras = Bundle().apply {
            putBinder("binder", binder)
        }

        val errors = mutableListOf<Throwable>()
        callCandidates().forEach { candidate ->
            runCatching {
                candidate.invoke(this, *candidate.arguments(authority, extras))
            }.onSuccess {
                return
            }.onFailure {
                errors += it
                Log.d(TAG, "IContentProvider.call/${candidate.parameterTypes.size} failed", it)
            }
        }
        throw IllegalStateException(
            "No compatible IContentProvider.call signature worked",
            errors.firstOrNull()
        )
    }

    private fun Any.callCandidates(): List<Method> {
        val preferredParamCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 5 else 6
        return javaClass.methods
            .filter { it.name == "call" }
            .sortedBy {
                val count = it.parameterTypes.size
                when {
                    count == preferredParamCount -> 0
                    count == 6 -> 1
                    count == 5 -> 2
                    count == 4 -> 3
                    count == 3 -> 4
                    else -> 5
                }
            }
    }

    private fun Method.arguments(authority: String, extras: Bundle): Array<Any?> {
        return when (parameterTypes.size) {
            5 -> {
                if (parameterTypes.firstOrNull()?.name == "android.content.AttributionSource") {
                    arrayOf(
                        AttributionSource.Builder(Process.myUid())
                            .setPackageName(lspApp.packageName)
                            .build(),
                        authority,
                        IXposedService.SEND_BINDER,
                        null,
                        extras
                    )
                } else {
                    arrayOf(lspApp.packageName, authority, IXposedService.SEND_BINDER, null, extras)
                }
            }
            6 -> arrayOf(lspApp.packageName, null, authority, IXposedService.SEND_BINDER, null, extras)
            4 -> arrayOf(lspApp.packageName, IXposedService.SEND_BINDER, null, extras)
            3 -> arrayOf(IXposedService.SEND_BINDER, null, extras)
            else -> error("Unsupported IContentProvider.call/${parameterTypes.size}")
        }
    }

    private fun currentUserId(): Int {
        return Process.myUserHandle().hashCode()
    }
}
