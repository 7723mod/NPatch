package org.lsposed.npatch

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
import org.lsposed.npatch.manager.AppBroadcastReceiver
import nkbe.util.NPackageManager
import nkbe.util.ShizukuApi
import java.io.File

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
        globalScope.launch { NPackageManager.fetchAppList() }
    }

    private fun verifySignature() {
        try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val packageInfo = packageManager.getPackageInfo(packageName, flags)
            val signingInfo = packageInfo.signingInfo
            val signatures = signingInfo?.apkContentsSigners

            if (signatures != null && signatures.isNotEmpty()) {
                val currentHash = signatures[0].hashCode()
                val targetHash = 0x0293FA43
                if (currentHash != targetHash) {
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
