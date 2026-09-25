package app.lernet.engine.compile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AssembledKeys {
    private val json = Json { ignoreUnknownKeys = true }

    fun summarize(compiledJson: String): String {
        val root = json.parseToJsonElement(compiledJson).jsonObject
        val tun = inboundTun(root)
        val route = root["route"] as? JsonObject
        val tunKeys = tun?.keys?.sorted()?.joinToString(",") ?: "-"
        val routeKeys = route?.keys?.sorted()?.joinToString(",") ?: "-"
        val rules = (route?.get("rules") as? JsonArray).orEmpty().map { element ->
            val rule = element.jsonObject
            buildString {
                append(rule["action"]?.jsonPrimitive?.content ?: "rule")
                if (rule.containsKey("inbound")) append("+inbound")
                if (rule["type"]?.jsonPrimitive?.content == "logical") append("+logical")
                if (rule.containsKey("protocol")) append("+protocol")
                if (rule.containsKey("port")) append("+port")
            }
        }.joinToString("|").ifBlank { "-" }
        return "tun=[$tunKeys] route=[$routeKeys] rules=[$rules]"
    }

    private fun inboundTun(root: JsonObject): JsonObject? {
        val inbounds = root["inbounds"] as? JsonArray ?: return null
        return inbounds.firstOrNull()?.jsonObject
    }
}
