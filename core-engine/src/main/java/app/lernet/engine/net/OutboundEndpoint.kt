package app.lernet.engine.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How a config's server can be checked from outside the tunnel. */
enum class ProbeTransport {
    /** TCP connect (vless, vmess, trojan, shadowsocks, …). */
    TCP,
    /** QUIC servers (hysteria, hysteria2, tuic): a version-negotiation request must be answered. */
    QUIC,
    /** UDP server that stays silent to strangers (obfuscated hysteria2, wireguard): cannot be checked in advance. */
    UNVERIFIABLE,
}

data class OutboundEndpoint(
    val host: String,
    val port: Int,
    val transport: ProbeTransport = ProbeTransport.TCP,
) {
    fun label(): String = "$host:$port"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(singBoxJson: String): OutboundEndpoint? {
            val obj = runCatching { json.parseToJsonElement(singBoxJson).jsonObject }.getOrNull() ?: return null
            return fromObject(obj)
        }

        fun fromAssembled(compiledJson: String, proxyTag: String?): OutboundEndpoint? {
            val root = runCatching { json.parseToJsonElement(compiledJson).jsonObject }.getOrNull() ?: return null
            val outbounds = root["outbounds"] as? kotlinx.serialization.json.JsonArray ?: return null
            val objects = outbounds.mapNotNull { it as? JsonObject }
            val tagged = if (!proxyTag.isNullOrBlank()) {
                objects.firstOrNull { it["tag"]?.jsonPrimitive?.contentOrNull == proxyTag }
            } else {
                null
            }
            val proxy = tagged
                ?: objects.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull !in setOf("direct", "block", "dns") }
            return proxy?.let(::fromObject)
        }

        private val quicTypes = setOf("hysteria", "hysteria2", "tuic")
        private val silentUdpTypes = setOf("wireguard")

        fun transportOf(obj: JsonObject): ProbeTransport {
            val type = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            return when {
                type in silentUdpTypes -> ProbeTransport.UNVERIFIABLE
                type in quicTypes && obj["obfs"] is JsonObject -> ProbeTransport.UNVERIFIABLE
                type in quicTypes -> ProbeTransport.QUIC
                else -> ProbeTransport.TCP
            }
        }

        private fun fromObject(obj: JsonObject): OutboundEndpoint? {
            val host = obj["server"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val port = obj["server_port"]?.jsonPrimitive?.intOrNull
            if (host.isBlank() || port == null || port !in 1..65535) return null
            return OutboundEndpoint(host, port, transportOf(obj))
        }
    }
}
