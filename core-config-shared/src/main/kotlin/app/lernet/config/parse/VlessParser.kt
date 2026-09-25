package app.lernet.config.parse

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource
import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object VlessParser {
    fun parse(rawLink: String): ImportResult {
        val checked = validate(rawLink.trim())
        if (checked is ImportResult.Failure) return checked
        val uri = URI(rawLink.trim())
        val uuid = decode(uri.userInfo.orEmpty())
        val host = uri.host.orEmpty().trim()
        val port = if (uri.port > 0) uri.port else 443
        val query = parseQuery(uri.rawQuery)
        val name = uri.fragment?.let { decode(it) }?.ifBlank { null } ?: host
        val outboundId = newId()
        val outbound = NormalizedOutbound(
            id = outboundId,
            tag = "proxy",
            type = "vless",
            singBoxJson = buildVlessOutbound("proxy", uuid, host, port, query).toString(),
        )
        return ImportResult.Success(
            listOf(
                ImportedProfileDraft(
                    name = name,
                    source = ProfileSource.VLESS,
                    outbounds = listOf(outbound),
                    selectedOutboundId = outboundId,
                    subscriptionUrl = null,
                ),
            ),
        )
    }

    internal fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&").mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val key = decode(pair.substringBefore("="))
            val value = if (pair.contains("=")) decode(pair.substringAfter("=")) else ""
            if (key.isBlank()) null else key.lowercase() to value
        }.toMap()
    }

    private fun validate(link: String): ImportResult? {
        val errors = buildList {
            if (link.isEmpty()) add(FieldError("link", "Пустая ссылка"))
            if (link.isNotEmpty() && !link.startsWith("vless://", ignoreCase = true)) {
                add(FieldError("link", "Ожидалась ссылка vless://"))
            }
        }
        if (errors.isNotEmpty()) return ImportResult.Failure(errors)
        val uri = runCatching { URI(link) }.getOrElse {
            return ImportResult.Failure(listOf(FieldError("link", "Ссылка не является допустимым URI")))
        }
        val fieldErrors = buildList {
            if (uri.userInfo.isNullOrBlank()) add(FieldError("uuid", "В ссылке нет UUID"))
            if (uri.host.isNullOrBlank()) add(FieldError("server", "В ссылке нет хоста"))
        }
        return if (fieldErrors.isEmpty()) null else ImportResult.Failure(fieldErrors)
    }

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun buildVlessOutbound(
        tag: String,
        uuid: String,
        host: String,
        port: Int,
        query: Map<String, String>,
    ): JsonObject = buildJsonObject {
        put("type", "vless")
        put("tag", tag)
        put("server", host)
        put("server_port", port)
        put("uuid", uuid)
        query["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
        val encryption = query["encryption"].orEmpty()
        if (encryption.isNotBlank() && encryption != "none") {
            put("packet_encoding", encryption)
        }
        putTls(this, host, query)
        putTransport(this, query)
    }

    private fun putTls(builder: JsonObjectBuilder, host: String, query: Map<String, String>) {
        val security = query["security"]?.lowercase().orEmpty()
        if (security != "tls" && security != "reality") return
        builder.putJsonObject("tls") {
            put("enabled", true)
            put("server_name", query["sni"].orEmpty().ifBlank { host })
            val insecure = query["allowinsecure"] ?: query["insecure"]
            if (insecure == "1" || insecure.equals("true", ignoreCase = true)) {
                put("insecure", true)
            }
            val alpn = query["alpn"].orEmpty()
            if (alpn.isNotBlank()) {
                put(
                    "alpn",
                    JsonArray(alpn.split(",").map { JsonPrimitive(it.trim()) }.filter { it.content.isNotBlank() }),
                )
            }
            query["fp"]?.takeIf { it.isNotBlank() }?.let { fp ->
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", fp)
                }
            }
            if (security == "reality") {
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", query["pbk"].orEmpty())
                    put("short_id", query["sid"].orEmpty())
                }
            }
        }
    }

    private fun putTransport(builder: JsonObjectBuilder, query: Map<String, String>) {
        val type = query["type"]?.lowercase().orEmpty()
        when (type) {
            "", "tcp" -> Unit
            "ws" -> builder.putJsonObject("transport") { putWs(query) }
            "grpc" -> builder.putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", query["servicename"] ?: query["serviceName"] ?: "")
            }
            "httpupgrade", "http", "h2" -> builder.putJsonObject("transport") { putHttpLike(type, query) }
            "xhttp", "splithttp" -> builder.putJsonObject("transport") { putXhttp(query) }
            else -> builder.putJsonObject("transport") { put("type", type) }
        }
    }

    private fun JsonObjectBuilder.putWs(query: Map<String, String>) {
        put("type", "ws")
        put("path", query["path"].orEmpty().ifBlank { "/" })
        val wsHost = query["host"].orEmpty()
        if (wsHost.isNotBlank()) {
            putJsonObject("headers") { put("Host", wsHost) }
        }
    }

    private fun JsonObjectBuilder.putXhttp(query: Map<String, String>) {
        put("type", "xhttp")
        put("path", query["path"].orEmpty().ifBlank { "/" })
        query["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
        put("mode", query["mode"].orEmpty().ifBlank { "auto" })
    }

    private fun JsonObjectBuilder.putHttpLike(type: String, query: Map<String, String>) {
        put("type", if (type == "h2") "http" else type)
        put("path", query["path"].orEmpty().ifBlank { "/" })
        val hostHeader = query["host"].orEmpty()
        if (hostHeader.isNotBlank()) {
            put("host", JsonArray(listOf(JsonPrimitive(hostHeader))))
        }
    }
}
