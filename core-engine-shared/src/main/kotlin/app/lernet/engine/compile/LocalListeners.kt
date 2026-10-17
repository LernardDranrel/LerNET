package app.lernet.engine.compile

import app.lernet.config.net.LocalProxyAccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads back the login/password of LerNET's own loopback listeners from a generated config. */
object LocalListeners {
    const val USER_PROXY_TAG = "mixed-in"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cache: Pair<String, Map<String, LocalProxyAccess>>? = null

    fun userProxy(config: String?): LocalProxyAccess? = read(config)[USER_PROXY_TAG]

    internal fun read(config: String?): Map<String, LocalProxyAccess> {
        if (config.isNullOrBlank()) return emptyMap()
        cache?.let { (raw, parsed) -> if (raw === config || raw == config) return parsed }
        val parsed = runCatching {
            json.parseToJsonElement(config).jsonObject["inbounds"]?.jsonArray.orEmpty().mapNotNull { element ->
                val inbound = element as? JsonObject ?: return@mapNotNull null
                if (inbound["type"]?.jsonPrimitive?.contentOrNull != "mixed") return@mapNotNull null
                val tag = inbound["tag"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val port = inbound["listen_port"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val user = inbound["users"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
                val name = user["username"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val password = user["password"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                tag to LocalProxyAccess(port, name, password)
            }.toMap()
        }.getOrDefault(emptyMap())
        cache = config to parsed
        return parsed
    }
}
