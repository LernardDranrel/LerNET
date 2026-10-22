package app.lernet.engine.compile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OwnedOutbound(
    val server: String = "",
    val port: String = "",
    val sni: String = "",
    val path: String = "",
    val mode: String = "",
    val reality: String = REALITY_ABSENT,
    val hasTransport: Boolean = false,
    val transportType: String = "",
    val xmuxConcurrency: String = "",
    /** TLS outbound (uTLS fingerprint applies). */
    val hasTls: Boolean = false,
    /** uTLS fingerprint written in the profile; "" = LerNET's default ([TlsHandshake.DEFAULT_FINGERPRINT]). */
    val fingerprint: String = "",
) {
    companion object {
        const val REALITY_PRESENT = "present"
        const val REALITY_ABSENT = "absent"
    }
}

object OutboundPatch {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(singBoxJson: String): OwnedOutbound {
        val root = parse(singBoxJson) ?: return OwnedOutbound()
        val tls = root["tls"] as? JsonObject
        val transport = root["transport"] as? JsonObject
        val xmux = transport?.get("xmux") as? JsonObject
        val reality = if (tls?.get("reality") is JsonObject) {
            OwnedOutbound.REALITY_PRESENT
        } else {
            OwnedOutbound.REALITY_ABSENT
        }
        return OwnedOutbound(
            server = text(root, "server"),
            port = root["server_port"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            sni = tls?.let { text(it, "server_name") }.orEmpty(),
            path = transport?.let { text(it, "path") }.orEmpty(),
            mode = transport?.let { text(it, "mode") }.orEmpty(),
            reality = reality,
            hasTransport = transport != null,
            transportType = transport?.let { text(it, "type") }.orEmpty(),
            xmuxConcurrency = xmux?.let { text(it, "max_concurrency") }.orEmpty(),
            hasTls = tls != null && tls["enabled"]?.jsonPrimitive?.contentOrNull != "false",
            fingerprint = (tls?.get("utls") as? JsonObject)?.let { text(it, "fingerprint") }.orEmpty(),
        )
    }

    /** [fingerprint]: null keeps what the profile has, "" falls back to the default, else that uTLS fingerprint. */
    fun write(
        singBoxJson: String,
        server: String,
        port: Int,
        sni: String,
        path: String,
        mode: String,
        fingerprint: String? = null,
    ): String {
        val root = parse(singBoxJson) ?: return singBoxJson
        val next = root.toMutableMap()
        next["server"] = JsonPrimitive(server.trim())
        next["server_port"] = JsonPrimitive(port)
        writeTls(root, next, sni)
        if (fingerprint != null) writeFingerprint(next, fingerprint.trim().lowercase())
        writeTransport(root, next, path, mode)
        return JsonObject(next).toString()
    }

    private fun writeTls(root: JsonObject, next: MutableMap<String, JsonElement>, sni: String) {
        val tls = root["tls"] as? JsonObject
        if (tls == null && sni.isBlank()) return
        val tlsNext = (tls?.toMutableMap() ?: mutableMapOf()).apply {
            put("server_name", JsonPrimitive(sni.trim()))
        }
        next["tls"] = JsonObject(tlsNext)
    }

    private fun writeFingerprint(next: MutableMap<String, JsonElement>, fingerprint: String) {
        val tls = next["tls"] as? JsonObject ?: return
        val utls = (tls["utls"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        if (fingerprint.isBlank()) {
            utls.remove("fingerprint")
        } else {
            utls["enabled"] = JsonPrimitive(true)
            utls["fingerprint"] = JsonPrimitive(fingerprint)
        }
        val tlsNext = tls.toMutableMap()
        if (utls.isEmpty()) tlsNext.remove("utls") else tlsNext["utls"] = JsonObject(utls)
        next["tls"] = JsonObject(tlsNext)
    }

    private fun writeTransport(
        root: JsonObject,
        next: MutableMap<String, JsonElement>,
        path: String,
        mode: String,
    ) {
        val transport = root["transport"] as? JsonObject ?: return
        val transportNext = transport.toMutableMap().apply {
            put("path", JsonPrimitive(path))
            if (mode.isBlank()) remove("mode") else put("mode", JsonPrimitive(mode))
        }
        next["transport"] = JsonObject(transportNext)
    }

    private fun parse(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun text(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
