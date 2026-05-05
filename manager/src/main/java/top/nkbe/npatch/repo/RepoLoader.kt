package top.nkbe.npatch.repo

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import top.nkbe.npatch.R
import top.nkbe.npatch.lspApp
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.collections.forEach

class RepoLoader private constructor() {

    var onlineModules: Map<String, OnlineModule> = HashMap()
        private set

    private var latestVersion: Map<String, ModuleVersion> = ConcurrentHashMap()

    class ModuleVersion(val versionCode: Long, val versionName: String) {
        fun upgradable(installedVersionCode: Long, installedVersionName: String?): Boolean {
            val safeVersionName = installedVersionName?.replace(' ', '_') ?: ""
            return this.versionCode > installedVersionCode ||
                    (this.versionCode == installedVersionCode && this.versionName != safeVersionName)
        }
    }

    private val repoFile: Path = Paths.get(lspApp.filesDir.absolutePath, "repo.json")
    private val listeners = ConcurrentHashMap.newKeySet<RepoListener>()

    @Volatile
    var isRepoLoaded = false
        private set

    private val resources = lspApp.resources

    private val channels: Array<String> = try {
        resources.getStringArray(R.array.update_channel_values)
    } catch (e: Exception) {
        arrayOf("release", "beta", "snapshot")
    }

    companion object {
        private const val TAG = "RepoLoader"
        private const val originRepoUrl = "https://modules.lsposed.org/"
        private const val backupRepoUrl = "https://modules-blogcdn.lsposed.org/"
        private const val secondBackupRepoUrl = "https://modules-cloudflare.lsposed.org/"

        private var repoUrl = originRepoUrl

        // 剥离原版 App.java 的依赖，直接在内部维护网络和线程池
        private val okHttpClient = OkHttpClient()
        private val executorService = Executors.newCachedThreadPool()

        @Volatile
        private var instance: RepoLoader? = null

        @JvmStatic
        fun getInstance(): RepoLoader {
            return instance ?: synchronized(this) {
                instance ?: RepoLoader().also {
                    instance = it
                    executorService.submit { it.loadLocalData(true) }
                }
            }
        }
    }

    @Synchronized
    fun loadRemoteData() {
        isRepoLoaded = false
        try {
            val request = Request.Builder().url(repoUrl + "modules.json").build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.string().let { bodyString ->
                        Files.write(repoFile, bodyString.toByteArray(StandardCharsets.UTF_8))
                        loadLocalData(false)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "load remote data", e)
            listeners.forEach { it.onThrowable(e) }
            when (repoUrl) {
                originRepoUrl -> {
                    repoUrl = backupRepoUrl
                    loadRemoteData()
                }
                backupRepoUrl -> {
                    repoUrl = secondBackupRepoUrl
                    loadRemoteData()
                }
            }
        }
    }

    @Synchronized
    fun loadLocalData(updateRemoteRepo: Boolean) {
        isRepoLoaded = false
        var doUpdateRemote = updateRemoteRepo
        try {
            if (Files.notExists(repoFile)) {
                loadRemoteData()
                doUpdateRemote = false
            }
            if (Files.exists(repoFile)) {
                val encoded = Files.readAllBytes(repoFile)
                val bodyString = String(encoded, StandardCharsets.UTF_8)
                val gson = Gson()
                val repoModules = gson.fromJson(bodyString, Array<OnlineModule>::class.java)

                val modules = HashMap<String, OnlineModule>()
                repoModules.forEach { module ->
                    module.name?.let { name ->
                        modules[name] = module
                    }
                }

                val prefs = lspApp.getSharedPreferences("${lspApp.packageName}_preferences", Context.MODE_PRIVATE)
                val channel = prefs.getString("update_channel", channels[0]) ?: channels[0]

                updateLatestVersion(repoModules, channel)
                onlineModules = modules
            }
        } catch (t: Throwable) {
            Log.e(TAG, Log.getStackTraceString(t))
            listeners.forEach { it.onThrowable(t) }
        } finally {
            isRepoLoaded = true
            listeners.forEach { it.onRepoLoaded() }
            if (doUpdateRemote) loadRemoteData()
        }
    }

    @Synchronized
    private fun updateLatestVersion(modules: Array<OnlineModule>, channel: String) {
        isRepoLoaded = false
        val versions = ConcurrentHashMap<String, ModuleVersion>()
        for (module in modules) {
            var release = module.latestRelease
            if (channel == channels[1] && !module.latestBetaRelease.isNullOrEmpty()) {
                release = module.latestBetaRelease
            } else if (channel == channels[2]) {
                if (!module.latestSnapshotRelease.isNullOrEmpty()) {
                    release = module.latestSnapshotRelease
                } else if (!module.latestBetaRelease.isNullOrEmpty()) {
                    release = module.latestBetaRelease
                }
            }

            if (release.isNullOrEmpty()) continue

            val splits = release.split("-", limit = 2)
            if (splits.size < 2) continue

            try {
                val verCode = splits[0].toLong()
                val verName = splits[1]
                module.name?.let { name ->
                    versions[name] = ModuleVersion(verCode, verName)
                }
            } catch (e: NumberFormatException) {
                continue
            }
        }
        latestVersion = versions
        isRepoLoaded = true
        listeners.forEach { it.onRepoLoaded() }
    }

    fun updateLatestVersion(channel: String) {
        if (isRepoLoaded) {
            updateLatestVersion(onlineModules.values.toTypedArray(), channel)
        }
    }

    fun getModuleLatestVersion(packageName: String): ModuleVersion? {
        return if (isRepoLoaded) latestVersion[packageName] else null
    }

    fun getReleases(packageName: String): List<Release> {
        val prefs = lspApp.getSharedPreferences("${lspApp.packageName}_preferences", Context.MODE_PRIVATE)
        val channel = prefs.getString("update_channel", channels[0]) ?: channels[0]
        var releases: List<Release> = ArrayList()

        if (isRepoLoaded) {
            val module = onlineModules[packageName]
            if (module != null) {
                releases = module.releases ?: emptyList()
                if (!module.releasesLoaded) {
                    if (channel == channels[1] && !module.betaReleases.isNullOrEmpty()) {
                        releases = module.betaReleases!!
                    } else if (channel == channels[2]) {
                        if (!module.snapshotReleases.isNullOrEmpty()) {
                            releases = module.snapshotReleases!!
                        } else if (!module.betaReleases.isNullOrEmpty()) {
                            releases = module.betaReleases!!
                        }
                    }
                }
            }
        }
        return releases
    }

    fun getLatestReleaseTime(packageName: String, channel: String): String? {
        var releaseTime: String? = null
        if (isRepoLoaded) {
            val module = onlineModules[packageName]
            if (module != null) {
                releaseTime = module.latestReleaseTime
                if (channel == channels[1] && module.latestBetaReleaseTime != null) {
                    releaseTime = module.latestBetaReleaseTime
                } else if (channel == channels[2]) {
                    if (module.latestSnapshotReleaseTime != null) {
                        releaseTime = module.latestSnapshotReleaseTime
                    } else if (module.latestBetaReleaseTime != null) {
                        releaseTime = module.latestBetaReleaseTime
                    }
                }
            }
        }
        return releaseTime
    }

    fun loadRemoteReleases(packageName: String) {
        val request = Request.Builder().url("${repoUrl}module/$packageName.json").build()
        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "${call.request().url} ${e.message}")
                when (repoUrl) {
                    originRepoUrl -> {
                        repoUrl = backupRepoUrl
                        loadRemoteReleases(packageName)
                    }
                    backupRepoUrl -> {
                        repoUrl = secondBackupRepoUrl
                        loadRemoteReleases(packageName)
                    }
                    else -> {
                        listeners.forEach { it.onThrowable(e) }
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    response.body.string().let { bodyString ->
                        try {
                            val gson = Gson()
                            val module = gson.fromJson(bodyString, OnlineModule::class.java)
                            module.releasesLoaded = true
                            (onlineModules as HashMap)[packageName] = module
                            listeners.forEach { it.onModuleReleasesLoaded(module) }
                        } catch (t: Throwable) {
                            Log.e(TAG, Log.getStackTraceString(t))
                            listeners.forEach { it.onThrowable(t) }
                        }
                    }
                }
            }
        })
    }

    fun addListener(listener: RepoListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: RepoListener) {
        listeners.remove(listener)
    }

    fun getOnlineModule(packageName: String?): OnlineModule? {
        return if (isRepoLoaded && packageName != null) onlineModules[packageName] else null
    }

    interface RepoListener {
        fun onRepoLoaded() {}
        fun onModuleReleasesLoaded(module: OnlineModule?) {}
        fun onThrowable(t: Throwable?) {
            Log.e(TAG, "load repo failed", t)
        }
    }
}
