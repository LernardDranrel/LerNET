package app.lernet.engine.compile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class DnsServerDraft(
    val index: Int,
    val type: String,
    val tag: String,
    val server: String,
    val detour: String,
)

object DnsDraft {
    private val needsAddress = setOf("udp", "tcp", "tls", "https", "quic", "h3")

    fun needsAddress(draft: DnsServerDraft): Boolean = draft.type.lowercase() in needsAddress

    fun read(dnsJson: String?): List<DnsServerDraft> {
        if (dnsJson.isNullOrBlank()) return emptyList()
        val dns = DnsBlock.normalize(dnsJson)
        val servers = dns["servers"] as? JsonArray ?: return emptyList()
        return servers.mapIndexedNotNull { index, element ->
            val server = element as? JsonObject ?: return@mapIndexedNotNull null
            DnsServerDraft(
                index = index,
                type = text(server, "type"),
                tag = text(server, "tag"),
                server = text(server, "server"),
                detour = text(server, "detour"),
            )
        }
    }

    fun write(dnsJson: String?, drafts: List<DnsServerDraft>): String? {
        if (drafts.isEmpty()) return dnsJson
        val base = if (dnsJson.isNullOrBlank()) emptyDns() else DnsBlock.normalize(dnsJson)
        val existing = ((base["servers"] as? JsonArray)?.toMutableList()) ?: mutableListOf()
        drafts.forEach { draft -> applyDraft(existing, draft) }
        val next = base.toMutableMap()
        next["servers"] = JsonArray(existing)
        if (base["final"] == null) {
            val tag = drafts.firstOrNull { it.tag.isNotBlank() }?.tag ?: DnsBlock.DIRECT_TAG
            next["final"] = JsonPrimitive(tag)
        }
        return JsonObject(next).toString()
    }

    private fun applyDraft(existing: MutableList<JsonElement>, draft: DnsServerDraft) {
        val current = existing.getOrNull(draft.index) as? JsonObject
        val type = draft.type.ifBlank { "udp" }
        val server = draft.server.trim()
        val next = if (current == null) {
            buildJsonObject {
                put("type", type)
                if (draft.tag.isNotBlank()) put("tag", draft.tag)
                if (server.isNotBlank()) put("server", server)
                if (draft.detour.isNotBlank()) put("detour", draft.detour)
            }
        } else {
            JsonObject(
                current.toMutableMap().apply {
                    put("type", JsonPrimitive(type))
                    if (server.isNotBlank() || needsAddress(draft)) put("server", JsonPrimitive(server))
                },
            )
        }
        if (draft.index < existing.size) {
            existing[draft.index] = next
        } else {
            existing += next
        }
    }

    private fun emptyDns(): JsonObject = buildJsonObject {
        put("servers", JsonArray(emptyList()))
        put("strategy", DnsBlock.STRATEGY_IPV4_ONLY)
    }

    private fun text(obj: JsonObject, key: String): String =
        obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
