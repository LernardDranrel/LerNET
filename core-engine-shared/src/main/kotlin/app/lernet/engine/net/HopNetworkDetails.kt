package app.lernet.engine.net

import app.lernet.config.net.AppTraffic
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Public routing information, not proof of ownership of a physical router. */
data class HopNetworkDetails(
    val asn: Int?,
    val holder: String?,
    val prefix: String?,
    val ptr: String?,
    val registeredTo: String?,
)

/** HTTPS fallback for ASN, announcing network and PTR when direct DNS TXT is blocked. */
class RipeStatHopDetails(
    private val fetch: (String, String) -> String? = ::ripeGet,
) {
    private val cache = ConcurrentHashMap<String, HopNetworkDetails>()

    @Volatile private var retryAfterMs: Long = 0

    fun lookup(ip: String): HopNetworkDetails? {
        if (IpScope.isSkipped(ip)) return null
        cache[ip]?.let { return it }
        if (System.currentTimeMillis() < retryAfterMs) return null
        val networkReply = fetch("network-info", ip)
        if (networkReply == null) {
            retryAfterMs = System.currentTimeMillis() + 60_000
            return null
        }
        val network = parseNetwork(networkReply)
        val holder = network.first?.let { asn -> parseHolder(fetch("as-overview", "AS$asn")) }
        val ptr = parsePtr(fetch("reverse-dns-ip", ip))
        val registeredTo = parseWhois(fetch("whois", ip))
        if (network.first == null && network.second == null && holder == null && ptr == null && registeredTo == null) return null
        return HopNetworkDetails(network.first, holder, network.second, ptr, registeredTo)
            .also { cache[ip] = it }
    }

    internal fun parseNetwork(raw: String?): Pair<Int?, String?> = runCatching {
        val data = data(raw) ?: return@runCatching null to null
        val asn = data["asns"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.toIntOrNull()
        val prefix = data["prefix"]?.jsonPrimitive?.content?.takeIf { it.contains('/') }
        asn to prefix
    }.getOrDefault(null to null)

    internal fun parseHolder(raw: String?): String? =
        runCatching { data(raw)?.get("holder")?.jsonPrimitive?.content?.trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }

    internal fun parsePtr(raw: String?): String? =
        runCatching { data(raw)?.get("result")?.jsonPrimitive?.content?.trim()?.trimEnd('.') }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() && it != "null" && it.length <= 253 }

    internal fun parseWhois(raw: String?): String? = runCatching {
        val records = data(raw)?.get("records")?.jsonArray ?: return@runCatching null
        val fields = records.flatMap { record -> record.jsonArray.map { it.jsonObject } }
        val keys = listOf("org-name", "orgname", "owner", "organization", "descr", "netname")
        keys.firstNotNullOfOrNull { wanted ->
            fields.firstOrNull { it["key"]?.jsonPrimitive?.content?.lowercase() == wanted }
                ?.get("value")?.jsonPrimitive?.content?.trim()
                ?.takeIf { it.isNotEmpty() && it != "null" && it.length <= 160 }
        }
    }.getOrNull()

    private fun data(raw: String?): JsonObject? = try {
        raw?.let { Json.parseToJsonElement(it).jsonObject["data"]?.jsonObject }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private val ripeClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
}

/** Public RIPEstat lookup; while the connection is on it goes only through the VPN. */
private fun ripeGet(endpoint: String, resource: String): String? {
    if (!resource.matches(Regex("[A-Za-z0-9:.]+"))) return null
    return try {
        val request = Request.Builder()
            .url("https://stat.ripe.net/data/$endpoint/data.json?resource=$resource")
            .header("Accept", "application/json")
            .build()
        AppTraffic.client(ripeClient).newCall(request).execute().use { response ->
            if (response.code != 200) return null
            val source = response.body?.source() ?: return null
            val buffer = Buffer()
            if (source.read(buffer, 65_537L) > 65_536L) return null
            while (buffer.size <= 65_536L && source.read(buffer, 65_537L - buffer.size) != -1L) Unit
            buffer.takeIf { it.size <= 65_536L }?.readUtf8()
        }
    } catch (error: java.io.IOException) {
        null
    }
}
