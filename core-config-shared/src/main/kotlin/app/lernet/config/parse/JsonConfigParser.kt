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
                listOf(FieldError("outbounds", "В JSON нет пригодного outbound (vless/trojan/ss/...)")),
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
