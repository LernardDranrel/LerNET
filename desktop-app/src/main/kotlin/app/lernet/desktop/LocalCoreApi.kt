package app.lernet.desktop

import java.net.ServerSocket
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class LiveConnection(
    val id: String,
    val host: String,
    val destination: String,
    val process: String,
    val rule: String,
    val chains: List<String>,
    val upload: Long,
    val download: Long,
    val started: String,
    val network: String,
    val active: Boolean = true,
    val lastSeenAt: Long = 0,
)

data class CoreDiagnostics(
    val uploadTotal: Long = 0,
    val downloadTotal: Long = 0,
    val connections: List<LiveConnection> = emptyList(),
    val error: String = "",
)

/** The core exposes its Clash API only on localhost with a per-launch bearer token. */
class LocalCoreApi(
    val port: Int = ServerSocket(0).use { it.localPort },
    private val secret: String = UUID.randomUUID().toString(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()

    fun inject(config: String): String {
        val root = json.parseToJsonElement(config).jsonObject
        val route = (root["route"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val hasTun = (root["inbounds"] as? JsonArray).orEmpty().any {
            (it as? JsonObject)?.string("type") == "tun"
        }
        if (hasTun) route["find_process"] = JsonPrimitive(true)
        val experimental = (root["experimental"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        experimental["clash_api"] = buildJsonObject {
            put("external_controller", "127.0.0.1:$port")
            put("secret", secret)
        }
        return JsonObject(root.toMutableMap().apply {
            put("route", JsonObject(route))
            put("experimental", JsonObject(experimental))
        }).toString()
    }

    fun poll(): CoreDiagnostics {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/connections"))
            .timeout(Duration.ofSeconds(3))
            .header("Authorization", "Bearer $secret")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "API ядра: HTTP ${response.statusCode()}" }
        val root = json.parseToJsonElement(response.body()).jsonObject
        val connections = (root["connections"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val metadata = row["metadata"] as? JsonObject ?: JsonObject(emptyMap())
            LiveConnection(
                id = row.string("id"),
                host = metadata.string("host"),
                destination = listOf(metadata.string("destinationIP"), metadata.string("destinationPort"))
                    .filter(String::isNotBlank).joinToString(":"),
                process = metadata.string("processPath").ifBlank { metadata.string("process") },
                rule = row.string("rule"),
                chains = (row["chains"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                upload = row.long("upload"),
                download = row.long("download"),
                started = row.string("start"),
                network = metadata.string("network"),
            )
        }
        return CoreDiagnostics(root.long("uploadTotal"), root.long("downloadTotal"), connections)
    }

    fun delay(outboundTag: String, url: String, timeoutMs: Int = 6_000): Long {
        val tag = URLEncoder.encode(outboundTag, Charsets.UTF_8).replace("+", "%20")
        val target = URLEncoder.encode(url, Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/proxies/$tag/delay?url=$target&timeout=$timeoutMs"))
            .timeout(Duration.ofMillis(timeoutMs.toLong() + 2_000))
            .header("Authorization", "Bearer $secret")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Проверка VPN: HTTP ${response.statusCode()}" }
        return json.parseToJsonElement(response.body()).jsonObject.long("delay")
            .takeIf { it > 0 } ?: error("Проверка VPN не вернула задержку")
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.longOrNull ?: 0L
}
