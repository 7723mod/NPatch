package top.nkbe.npatch.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.nkbe.npatch.BuildConfig

sealed interface SystemInstallResult {
    data class Completed(val status: Int, val message: String?) : SystemInstallResult
    data object PermissionRequired : SystemInstallResult
}

object SystemPackageInstaller {
    const val ACTION_INSTALL_STATUS = "${BuildConfig.APPLICATION_ID}.INSTALL_STATUS"
    private const val EXTRA_REQUEST_TOKEN = "requestToken"
    private val requests = ConcurrentHashMap<String, CompletableDeferred<SystemInstallResult.Completed>>()

    suspend fun install(context: Context, installSet: ApkInstallSet): SystemInstallResult {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = "package:${context.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
            return SystemInstallResult.PermissionRequired
        }

        val appContext = context.applicationContext
        val packageInstaller = appContext.packageManager.packageInstaller
        return withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(installSet.packageName)
                setSize(installSet.totalSize)
            }
            val sessionId = packageInstaller.createSession(params)
            val token = UUID.randomUUID().toString()
            val completion = CompletableDeferred<SystemInstallResult.Completed>()
            requests[token] = completion
            var committed = false
            try {
                packageInstaller.openSession(sessionId).use { session ->
                    installSet.entries.forEach { entry ->
                        session.openWrite(entry.sessionName, 0L, entry.file.length()).use { output ->
                            entry.file.inputStream().use { input -> input.copyTo(output) }
                            session.fsync(output)
                        }
                    }
                    session.commit(createStatusIntent(appContext, sessionId, token).intentSender)
                    committed = true
                }
                val result = kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                    completion.await()
                }
                result ?: SystemInstallResult.Completed(
                    PackageInstaller.STATUS_FAILURE,
                    "Installation timed out (60s). Please check if system installer or permission prompt was cancelled/blocked.",
                )
            } catch (error: Throwable) {
                if (!committed) runCatching { packageInstaller.abandonSession(sessionId) }
                throw error
            } finally {
                requests.remove(token, completion)
            }
        }
    }

    fun onInstallStatus(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val token = intent.getStringExtra(EXTRA_REQUEST_TOKEN) ?: return
        val completion = requests[token] ?: return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirmation == null) {
                completion.complete(
                    SystemInstallResult.Completed(
                        PackageInstaller.STATUS_FAILURE,
                        "Package installer did not provide a confirmation intent",
                    ),
                )
            } else {
                runCatching {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { error ->
                    completion.complete(
                        SystemInstallResult.Completed(
                            PackageInstaller.STATUS_FAILURE,
                            error.message ?: "Unable to open package installer confirmation",
                        ),
                    )
                }
            }
            return
        }
        completion.complete(
            SystemInstallResult.Completed(
                status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            ),
        )
    }

    private fun createStatusIntent(context: Context, sessionId: Int, token: String): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = ACTION_INSTALL_STATUS
            data = "npatch-install://result/$token".toUri()
            putExtra(EXTRA_REQUEST_TOKEN, token)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SystemPackageInstaller.onInstallStatus(context, intent)
    }
}
