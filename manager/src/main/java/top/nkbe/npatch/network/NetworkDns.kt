package top.nkbe.npatch.network

import android.content.Context
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import top.nkbe.npatch.lspApp
import java.net.InetAddress

enum class DnsProvider(val preferenceValue: String) {
    TENCENT("tencent"),
    GOOGLE("google"),
    CLOUDFLARE("cloudflare"),
    SYSTEM("system"),
    CUSTOM("custom");

    companion object {
        fun fromPreference(value: String?): DnsProvider =
            entries.firstOrNull { it.preferenceValue == value } ?: TENCENT
    }
}

object NetworkDns {
    const val PREF_PROVIDER = "dns_provider"
    const val PREF_CUSTOM_URL = "dns_custom_url"
    private data class ClientKey(val provider: DnsProvider, val customUrl: String)

    @Volatile
    private var cachedClient: Pair<ClientKey, OkHttpClient>? = null

    fun selectedProvider(): DnsProvider = DnsProvider.fromPreference(
        preferences().getString(PREF_PROVIDER, DnsProvider.TENCENT.preferenceValue)
    )

    fun customUrl(): String = preferences().getString(PREF_CUSTOM_URL, "").orEmpty()

    fun setProvider(provider: DnsProvider) {
        preferences().edit().putString(PREF_PROVIDER, provider.preferenceValue).apply()
        cachedClient = null
    }

    fun setCustomUrl(url: String): Boolean {
        val normalized = url.trim()
        val parsed = normalized.toHttpUrlOrNull()
        if (parsed == null || !parsed.isHttps || parsed.host.isBlank()) return false
        preferences().edit()
            .putString(PREF_CUSTOM_URL, normalized)
            .putString(PREF_PROVIDER, DnsProvider.CUSTOM.preferenceValue)
            .apply()
        cachedClient = null
        return true
    }

    fun client(): OkHttpClient {
        val key = ClientKey(selectedProvider(), customUrl())
        cachedClient?.takeIf { it.first == key }?.let { return it.second }
        return synchronized(this) {
            cachedClient?.takeIf { it.first == key }?.second ?: buildClient(key).also {
                cachedClient = key to it
            }
        }
    }

    private fun buildClient(key: ClientKey): OkHttpClient {
        if (key.provider == DnsProvider.SYSTEM) return OkHttpClient()

        val (url, bootstrapAddresses) = when (key.provider) {
            DnsProvider.TENCENT -> "https://doh.pub/dns-query" to listOf("1.12.12.12", "120.53.53.53")
            DnsProvider.GOOGLE -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
            DnsProvider.CLOUDFLARE -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
            DnsProvider.CUSTOM -> key.customUrl to emptyList()
            DnsProvider.SYSTEM -> error("Handled above")
        }
        val parsedUrl = url.toHttpUrlOrNull() ?: return OkHttpClient()
        val bootstrapClient = OkHttpClient()
        val builder = DnsOverHttps.Builder()
            .client(bootstrapClient)
            .url(parsedUrl)
            .post(true)
            .includeIPv6(true)
            .systemDns(Dns.SYSTEM)
        if (bootstrapAddresses.isNotEmpty()) {
            builder.bootstrapDnsHosts(bootstrapAddresses.map { InetAddress.getByName(it) })
        }
        return bootstrapClient.newBuilder().dns(builder.build()).build()
    }

    private fun preferences() =
        lspApp.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
