package app.lernet.engine.compile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Inventory of fields removed by sing-box 1.13/1.14.
 * Source: https://sing-box.sagernet.org/migration/ and /deprecated/
 */
object SingBox114Guard {
    private val json = Json { ignoreUnknownKeys = true }

    private val inboundRemoved = setOf(
        "sniff",
        "sniff_timeout",
        "domain_strategy",
        "inet4_address",
        "inet6_address",
        "inet4_route_address",
        "inet6_route_address",
        "inet4_route_exclude_address",
        "inet6_route_exclude_address",
        "gso",
    )

    private val specialOutboundTypes = setOf("block", "dns")

    private val routeRemoved = setOf("geoip", "geosite", "source_geoip", "source_geosite")

    fun legacyProblems(compiledJson: String): List<String> {
        val root = runCatching { json.parseToJsonElement(compiledJson).jsonObject }.getOrNull()
            ?: return listOf("compiled JSON is not an object")
        return buildList {
            addAll(DnsBlock.legacyProblems(compiledJson))
            addAll(inboundProblems(root["inbounds"]))
            addAll(outboundProblems(root["outbounds"]))
            addAll(routeProblems(root["route"] as? JsonObject))
            addAll(dnsRuleProblems(root["dns"] as? JsonObject))
        }
    }

    private fun inboundProblems(inbounds: JsonElement?): List<String> {
        val array = inbounds as? JsonArray ?: return listOf("inbounds is missing")
        return array.flatMapIndexed { index, element ->
            val obj = element as? JsonObject ?: return@flatMapIndexed listOf("inbounds[$index] is not an object")
            inboundRemoved.filter { obj.containsKey(it) }.map { key ->
                "inbounds[$index] uses removed field $key"
            }
        }
    }

    private fun outboundProblems(outbounds: JsonElement?): List<String> {
        val array = outbounds as? JsonArray ?: return listOf("outbounds is missing")
        return array.flatMapIndexed { index, element ->
            val obj = element as? JsonObject ?: return@flatMapIndexed listOf("outbounds[$index] is not an object")
            val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
            buildList {
                if (type in specialOutboundTypes) {
                    add("outbounds[$index] uses removed special outbound type $type")
                }
                if (type == "wireguard") {
                    add("outbounds[$index] uses removed wireguard outbound (use endpoint)")
                }
                if (type == "direct") {
                    if (obj.containsKey("override_address") || obj.containsKey("override_port")) {
                        add("outbounds[$index] uses removed direct override_* fields")
                    }
                }
            }
        }
    }

    private fun routeProblems(route: JsonObject?): List<String> {
        if (route == null) return listOf("route is missing")
        val problems = mutableListOf<String>()
        if (route.containsKey("geoip") || route.containsKey("geosite")) {
            problems += "route uses removed geoip/geosite database"
        }
        val rules = route["rules"] as? JsonArray ?: return problems
        rules.forEachIndexed { index, element ->
            val obj = element as? JsonObject ?: return@forEachIndexed
            routeRemoved.filter { obj.containsKey(it) }.forEach { key ->
                problems += "route.rules[$index] uses removed field $key"
            }
            if (obj["outbound"]?.jsonPrimitive?.contentOrNull == "block") {
                problems += "route.rules[$index] targets removed block outbound"
            }
            if (obj["outbound"]?.jsonPrimitive?.contentOrNull == "dns") {
                problems += "route.rules[$index] targets removed dns outbound"
            }
        }
        if (route["final"]?.jsonPrimitive?.contentOrNull == "block") {
            problems += "route.final targets removed block outbound"
        }
        return problems
    }

    private fun dnsRuleProblems(dns: JsonObject?): List<String> {
        if (dns == null) return emptyList()
        val rules = dns["rules"] as? JsonArray ?: return emptyList()
        return rules.flatMapIndexed { index, element ->
            val obj = element as? JsonObject ?: return@flatMapIndexed emptyList()
            buildList {
                if (obj.containsKey("outbound")) add("dns.rules[$index] uses removed outbound matcher")
                if (obj.containsKey("geoip") || obj.containsKey("geosite")) {
                    add("dns.rules[$index] uses removed geoip/geosite")
                }
            }
        }
    }
}
