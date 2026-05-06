package top.nkbe.npatch

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.os.Process
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import top.nkbe.npatch.manager.AppBroadcastReceiver
import nkbe.util.NeoPackageManager
import nkbe.util.ShizukuApi
import java.io.File
import java.security.MessageDigest

lateinit var lspApp: LSPApplication

class LSPApplication : Application() {

    lateinit var prefs: SharedPreferences
    lateinit var tmpApkDir: File

    var targetApkFiles: ArrayList<File>? = null
    val globalScope = CoroutineScope(Dispatchers.Default)


    override fun attachBaseContext(base: Context) {
        val prefs = base.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "") ?: ""
        super.attachBaseContext(applyLocale(base, language))
    }

    override fun onCreate() {
        super.onCreate()
        verifySignature()

        try {
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        HiddenApiBypass.addHiddenApiExemptions("")
        lspApp = this
        filesDir.mkdir()
        tmpApkDir = cacheDir.resolve("apk").also { it.mkdir() }
        prefs = lspApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
        ShizukuApi.init()
        AppBroadcastReceiver.register(this)
        globalScope.launch { NeoPackageManager.fetchAppList() }
    }

    private fun verifySignature() {
        try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val packageInfo = packageManager.getPackageInfo(packageName, flags)
            val signingInfo = packageInfo.signingInfo
            val signatures = signingInfo?.apkContentsSigners

            if (signatures != null && signatures.isNotEmpty()) {
                val allowlist = setOf(
                    "DB73788534AFFC4BFA3AE16040A2D3A2C2B63EDEA1E07F3A1CF9AFF4DD0995A8"
                )
                val matched = signatures.any { signature ->
                    val sha256 = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                        .joinToString("") { "%02X".format(it) }
                    allowlist.contains(sha256)
                }
                if (!matched) {
                    killApp()
                }
            } else {
                killApp()
            }
        } catch (e: Exception) {
            killApp()
        }
    }

    private fun killApp() {
        Process.killProcess(Process.myPid())
    }

    companion object {
        fun applyLocale(context: Context, languageTag: String): Context {
            if (languageTag.isEmpty()) return context
            val locale = Locale.forLanguageTag(languageTag)
            Locale.setDefault(locale)
            val config = Configuration(context.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            return context.createConfigurationContext(config)
        }
    }
}
