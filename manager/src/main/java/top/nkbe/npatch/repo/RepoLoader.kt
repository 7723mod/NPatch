package top.nkbe.npatch.repo

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import top.nkbe.npatch.R
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.network.NetworkDns
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RepoLoader private constructor() {

    var onlineModules: Map<String, OnlineModule> = ConcurrentHashMap()
        private set

    private var latestVersion: Map<String, ModuleVersion> = ConcurrentHashMap()

    class ModuleVersion(val versionCode: Long, val versionName: String) {
        fun upgradable(installedVersionCode: Long, installedVersionName: String?): Boolean {
            val safeVersionName = installedVersionName?.replace(' ', '_') ?: ""
            return versionCode > installedVersionCode ||
                (versionCode == installedVersionCode && versionName != safeVersionName)
        }
    }

    private val repoFile: Path = Paths.get(lspApp.filesDir.absolutePath, "repo.json")
    private val listeners = ConcurrentHashMap.newKeySet<RepoListener>()
    private val loadLock = ReentrantLock()
    private val isRefreshing = AtomicBoolean(false)

    @Volatile
    var isRepoLoaded = false
        private set

    val hasLocalRepo: Boolean
        get() = Files.exists(repoFile)

    private val resources = lspApp.resources

    private val channels: Array<String> = try {
        resources.getStringArray(R.array.update_channel_values)
    } catch (_: Exception) {
        arrayOf("release", "beta", "snapshot")
    }

    companion object {
        private const val TAG = "RepoLoader"
        private const val repoBaseUrl = "https://repo.fpfast.top/repo/"

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

    fun loadRemoteData() {
        if (isRefreshing.getAndSet(true)) return
        executorService.submit {
            try {
                val request = Request.Builder().url("${repoBaseUrl}modules").build()
                NetworkDns.client().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    response.body.byteStream().use { input ->
                        Files.copy(input, repoFile, StandardCopyOption.REPLACE_EXISTING)
                    }
                    loadLocalData(false)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "load remote data", e)
                listeners.forEach { it.onThrowable(e) }
            } finally {
                isRefreshing.set(false)
            }
        }
    }

    fun loadLocalData(updateRemoteRepo: Boolean) {
        loadLock.withLock {
            var doUpdateRemote = updateRemoteRepo
            try {
                if (Files.notExists(repoFile)) {
                    loadRemoteData()
                    doUpdateRemote = false
                }
                if (Files.exists(repoFile)) {
                    val repoModules = Files.newInputStream(repoFile).use { input ->
                        InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                            parseRepoModules(reader)
                        }
                    }

                    val modules = ConcurrentHashMap<String, OnlineModule>()
                    repoModules.forEach { LoadedModule ->
                        LoadedModule.name?.let { name ->
                            modules[name] = LoadedModule
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
    }

    private fun parseRepoModules(reader: InputStreamReader): Array<OnlineModule> {
        // We still need to check if it's an array or object
        // For simplicity, we can peek or just try parsing as JsonElement
        val element = JsonParser.parseReader(reader)
        return if (element.isJsonArray) {
            Gson().fromJson(element, Array<OnlineModule>::class.java)
        } else {
            val root = element.asJsonObject
            val modulesArray = root.getAsJsonArray("modules") ?: return emptyArray()
            Array(modulesArray.size()) { index ->
                mapFpaModuleSummary(modulesArray[index].asJsonObject)
            }
        }
    }

    private fun mapFpaModuleSummary(json: JsonObject): OnlineModule {
        val LoadedModule = OnlineModule()
        val packageName = json.optString("pkg")
        val versionCode = json.optLong("new_version_code")
        val versionName = json.optString("new_version")
        val latestReleaseTime = epochMillisToIso(json.optLong("new_update_time"))

        LoadedModule.name = packageName
        LoadedModule.description = json.optString("desc")
        LoadedModule.summary = json.optString("summary")
        LoadedModule.readmeHTML = json.optString("readme_html")
        LoadedModule.readme = json.optString("readme_text")
        LoadedModule.createdAt = epochMillisToIso(json.optLong("createTime"))
        LoadedModule.updatedAt = latestReleaseTime
        LoadedModule.latestReleaseTime = latestReleaseTime
        LoadedModule.homepageUrl = packageName?.let(::getModulePageUrl)
        LoadedModule.collaborators = listOf(parseAuthor(json.optString("author")))
        LoadedModule.scope =
            buildList {
                addAll(json.optStringList("xp89scope"))
                addAll(json.optStringList("xp100scope"))
            }.distinct()

        if (versionCode > 0L && !versionName.isNullOrEmpty()) {
            LoadedModule.latestRelease = "$versionCode-$versionName"
        }

        return LoadedModule
    }

    @Synchronized
    private fun updateLatestVersion(modules: Array<OnlineModule>, channel: String) {
        isRepoLoaded = false
        val versions = ConcurrentHashMap<String, ModuleVersion>()
        for (LoadedModule in modules) {
            var release = LoadedModule.latestRelease
            if (channel == channels[1] && !LoadedModule.latestBetaRelease.isNullOrEmpty()) {
                release = LoadedModule.latestBetaRelease
            } else if (channel == channels[2]) {
                if (!LoadedModule.latestSnapshotRelease.isNullOrEmpty()) {
                    release = LoadedModule.latestSnapshotRelease
                } else if (!LoadedModule.latestBetaRelease.isNullOrEmpty()) {
                    release = LoadedModule.latestBetaRelease
                }
            }

            if (release.isNullOrEmpty()) continue

            val splits = release.split("-", limit = 2)
            if (splits.size < 2) continue

            try {
                val verCode = splits[0].toLong()
                val verName = splits[1]
                LoadedModule.name?.let { name ->
                    versions[name] = ModuleVersion(verCode, verName)
                }
            } catch (_: NumberFormatException) {
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
            val LoadedModule = onlineModules[packageName]
            if (LoadedModule != null) {
                releases = LoadedModule.releases
                if (!LoadedModule.releasesLoaded) {
                    if (channel == channels[1] && LoadedModule.betaReleases.isNotEmpty()) {
                        releases = LoadedModule.betaReleases
                    } else if (channel == channels[2]) {
                        if (LoadedModule.snapshotReleases.isNotEmpty()) {
                            releases = LoadedModule.snapshotReleases
                        } else if (LoadedModule.betaReleases.isNotEmpty()) {
                            releases = LoadedModule.betaReleases
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
            val LoadedModule = onlineModules[packageName]
            if (LoadedModule != null) {
                releaseTime = LoadedModule.latestReleaseTime
                if (channel == channels[1] && LoadedModule.latestBetaReleaseTime != null) {
                    releaseTime = LoadedModule.latestBetaReleaseTime
                } else if (channel == channels[2]) {
                    if (LoadedModule.latestSnapshotReleaseTime != null) {
                        releaseTime = LoadedModule.latestSnapshotReleaseTime
                    } else if (LoadedModule.latestBetaReleaseTime != null) {
                        releaseTime = LoadedModule.latestBetaReleaseTime
                    }
                }
            }
        }
        return releaseTime
    }

    fun loadRemoteReleases(packageName: String) {
        val request = Request.Builder().url("${repoBaseUrl}info/$packageName").build()
        NetworkDns.client().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "${call.request().url} ${e.message}")
                listeners.forEach { it.onThrowable(e) }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    listeners.forEach { it.onThrowable(IOException("HTTP ${response.code}")) }
                    return
                }

                response.body.string().let { bodyString ->
                    try {
                        val LoadedModule = (onlineModules[packageName] ?: OnlineModule().apply {
                            name = packageName
                        })
                        val root = JsonParser.parseString(bodyString).asJsonObject
                        val versions = root.getAsJsonArray("modules")
                        val releases = versions?.map { mapFpaRelease(packageName, it.asJsonObject) } ?: emptyList()
                        LoadedModule.releases = releases
                        LoadedModule.releasesLoaded = true
                        (onlineModules as MutableMap)[packageName] = LoadedModule
                        listeners.forEach { it.onModuleReleasesLoaded(LoadedModule) }
                    } catch (t: Throwable) {
                        Log.e(TAG, Log.getStackTraceString(t))
                        listeners.forEach { it.onThrowable(t) }
                    }
                }
            }
        })
    }

    private fun mapFpaRelease(packageName: String, json: JsonObject): Release {
        val release = Release()
        val versionCode = json.optLong("version_code")
        val versionName = json.optString("version")
        val tag = json.optString("tag")
        val fileName = json.optString("file_name")
        val versionTime = epochMillisToIso(json.optLong("version_time"))

        release.name =
            buildString {
                if (!versionName.isNullOrEmpty()) append(versionName)
                if (versionCode > 0L) {
                    if (isNotEmpty()) append(" ")
                    append("($versionCode)")
                }
            }.ifEmpty { tag }
        release.tagName = tag
        release.createdAt = versionTime
        release.publishedAt = versionTime
        release.updatedAt = versionTime
        release.description = json.optString("desc_text")
        release.descriptionHTML = json.optString("desc_html")
        release.releaseAssets =
            if (tag.isNullOrEmpty() || fileName.isNullOrEmpty()) {
                emptyList()
            } else {
                listOf(
                    ReleaseAsset().apply {
                        name = fileName
                        downloadUrl = getModuleFileUrl(packageName, tag, fileName)
                    }
                )
            }
        return release
    }

    private fun parseAuthor(author: String?): Collaborator {
        val collaborator = Collaborator()
        val raw = author?.trim().orEmpty()
        val match = Regex("""^(.+?)\(([^()]+)\)$""").find(raw)
        if (match != null) {
            collaborator.name = match.groupValues[1].trim()
            collaborator.login = match.groupValues[2].trim()
        } else if (raw.isNotEmpty()) {
            collaborator.name = raw
        }
        return collaborator
    }

    private fun epochMillisToIso(value: Long): String? {
        if (value <= 0L) return null
        return Instant.ofEpochMilli(value).toString()
    }

    private fun JsonObject.optString(name: String): String? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        return element.asString
    }

    private fun JsonObject.optLong(name: String): Long {
        val element = get(name) ?: return 0L
        if (element.isJsonNull) return 0L
        return runCatching { element.asLong }.getOrDefault(0L)
    }

    private fun JsonObject.optStringList(name: String): List<String> {
        val array = getAsJsonArray(name) ?: return emptyList()
        return array.mapNotNull { element ->
            if (element == null || element.isJsonNull) null else element.asString
        }
    }

    fun getModulePageUrl(packageName: String): String = "${repoBaseUrl}info/$packageName"

    fun getModuleFileUrl(packageName: String, tag: String, fileName: String): String =
        "${repoBaseUrl}file/$packageName/$tag/$fileName"

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
        fun onModuleReleasesLoaded(LoadedModule: OnlineModule?) {}
        fun onThrowable(t: Throwable?) {
            Log.e(TAG, "load repo failed", t)
        }
    }
}
