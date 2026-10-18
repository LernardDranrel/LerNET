package app.lernet.config.parse

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object JsonConfigParser {
    private val json = Json { ignoreUnknownKeys = true }

    private val ignoredTypes = setOf(
        "direct",
        "block",
        "dns",
        "selector",
        "urltest",
        "dummy",
    )

    fun parse(
        raw: String,
        source: ProfileSource,
        defaultName: String,
        subscriptionUrl: String? = null,
    ): ImportResult {
        val text = raw.trim()
        if (text.isEmpty()) {
            return ImportResult.Failure(listOf(FieldError("json", "Пустой JSON")))
        }
        val element = runCatching { json.parseToJsonElement(text) }.getOrElse {
            return ImportResult.Failure(listOf(FieldError("json", "Некорректный JSON")))
        }
        val objects = extractOutboundObjects(element)
        if (objects.isEmpty()) {
            return ImportResult.Failure(
                listOf(FieldError("outbounds", "В JSON нет подходящего сервера (vless, trojan, shadowsocks…)")),
            )
        }
        val outbounds = objects.mapIndexed { index, obj ->
            val type = obj.string("type") ?: "unknown"
            val tag = obj.string("tag")?.ifBlank { null } ?: "proxy-$index"
            NormalizedOutbound(
                id = newId(),
                tag = tag,
                type = type,
                singBoxJson = obj.toString(),
            )
        }
        val name = element.nameHint() ?: defaultName
        return ImportResult.Success(
            listOf(
                ImportedProfileDraft(
                    name = name,
                    source = source,
                    outbounds = outbounds,
                    selectedOutboundId = outbounds.first().id,
                    subscriptionUrl = subscriptionUrl,
                    dnsJson = extractDns(element),
                    unusedParts = unusedParts(element),
                ),
            ),
        )
    }

    private fun extractOutboundObjects(element: JsonElement): List<JsonObject> = when (element) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }.filter { it.isConcreteOutbound() }
        is JsonObject -> {
            val nested = element["outbounds"]
            if (nested is JsonArray) {
                nested.mapNotNull { it as? JsonObject }.filter { it.isConcreteOutbound() }
            } else if (element.isConcreteOutbound()) {
                listOf(element)
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }

    /** Top-level keys LerNET reads: outbounds (concrete ones), dns, and the name hint. */
    private val usedKeys = setOf("outbounds", "dns", "name", "tag")

    /**
     * Lists what the import silently dropped before: route rules, inbounds,
     * outbound groups and service outbounds, other top-level sections.
     * A bare outbound (or an array of them) has nothing to drop except service/group entries.
     */
    fun unusedParts(element: JsonElement): List<String> {
        val parts = mutableListOf<String>()
        val outboundList: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> (element["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            else -> emptyList()
        }
        val root = element as? JsonObject
        if (root != null && root["outbounds"] is JsonArray) {
            val route = root["route"] as? JsonObject
            if (route != null) {
                val rules = (route["rules"] as? JsonArray)?.size ?: 0
                val ruleSets = (route["rule_set"] as? JsonArray)?.size ?: 0
                val counted = buildList {
                    if (rules > 0) add("правил: $rules")
                    if (ruleSets > 0) add("наборов правил: $ruleSets")
                }.joinToString(", ")
                parts += "маршрутизация (route${if (counted.isNotEmpty()) ", $counted" else ""}) — работают правила LerNET"
            }
            val inbounds = (root["inbounds"] as? JsonArray)?.size ?: 0
            if (inbounds > 0) parts += "входы (inbounds: $inbounds) — LerNET создаёт свои"
        }
        val groups = outboundList.filter { it.string("type")?.lowercase() in groupTypes }.map { it.label() }
        if (groups.isNotEmpty()) parts += "группы selector/urltest: ${groups.joinToString(", ")} — выбор сервера делает LerNET"
        val service = outboundList.filter { it.string("type")?.lowercase() in serviceTypes }.map { it.label() }
        if (service.isNotEmpty()) parts += "служебные outbound (direct/block/dns): ${service.joinToString(", ")} — у LerNET свои"
        val untyped = outboundList.count { it.string("type") == null }
        if (untyped > 0) parts += "outbound без типа: $untyped"
        if (root != null && root["outbounds"] is JsonArray) {
            root.keys.filter { it !in usedKeys && it != "route" && it != "inbounds" }.sorted().forEach { key ->
                parts += "раздел «$key»"
            }
        }
        return parts
    }

    private val groupTypes = setOf("selector", "urltest")
    private val serviceTypes = setOf("direct", "block", "dns", "dummy")

    private fun JsonObject.label(): String = string("tag")?.ifBlank { null } ?: string("type") ?: "?"

    private fun JsonObject.isConcreteOutbound(): Boolean {
        val type = string("type")?.lowercase() ?: return false
        return type !in ignoredTypes
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun extractDns(element: JsonElement): String? {
        val dns = (element as? JsonObject)?.get("dns") as? JsonObject ?: return null
        return dns.toString()
    }

    private fun JsonElement.nameHint(): String? {
        val obj = this as? JsonObject ?: return null
        return obj.string("name") ?: obj.string("tag")
    }
}
