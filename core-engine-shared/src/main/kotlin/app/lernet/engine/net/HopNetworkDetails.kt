package app.lernet.engine.net

import java.net.HttpURLConnection
import java.net.URL
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

private fun ripeGet(endpoint: String, resource: String): String? {
    if (!resource.matches(Regex("[A-Za-z0-9:.]+"))) return null
    var connection: HttpURLConnection? = null
    return try {
        val open = URL("https://stat.ripe.net/data/$endpoint/data.json?resource=$resource")
            .openConnection() as HttpURLConnection
        connection = open
        open.connectTimeout = 2_000
        open.readTimeout = 2_000
        open.setRequestProperty("Accept", "application/json")
        if (open.responseCode != HttpURLConnection.HTTP_OK) return null
        open.inputStream.bufferedReader().use { reader ->
            reader.readText().takeIf { it.length <= 65_536 }
        }
    } catch (error: java.io.IOException) {
        null
    } finally {
        connection?.disconnect()
    }
}
