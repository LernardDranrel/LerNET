package app.lernet.engine.compile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * sing-box 1.14 DNS server graph:
 * `domain_resolver` / `address_resolver` must name an existing `dns.servers[].tag`.
 * `detour` must name an existing outbound that is not an empty `direct`
 * (sing-box-lx 1.14.1 `dialer.DetourDialer`: "detour to an empty direct outbound makes no sense").
 * Resolver edges must be acyclic.
 * `dns.final` and `dns.rules[].server` must name a DNS server tag.
 * `route.default_domain_resolver` is the same resolver reference.
 */
object DnsDependency {
    /**
     * JSON keys that make `protocol/direct.Outbound.IsEmpty` false on lx.8.
     * `{"type":"direct","tag":"direct"}` matches `DialerOptions{UDPFragmentDefault:true}`.
     */
    private val NON_EMPTY_DIRECT_KEYS = setOf(
        "detour",
        "bind_interface",
        "inet4_bind_address",
        "inet6_bind_address",
        "bind_address_no_port",
        "protect_path",
        "routing_mark",
        "reuse_addr",
        "netns",
        "connect_timeout",
        "tcp_fast_open",
        "tcp_multi_path",
        "disable_tcp_keep_alive",
        "tcp_keep_alive",
        "tcp_keep_alive_interval",
        "udp_fragment",
        "domain_resolver",
        "network_strategy",
        "network_type",
        "fallback_network_type",
        "fallback_delay",
        "domain_strategy",
    )

    fun dangling(root: JsonObject): List<String> {
        val dns = root["dns"] as? JsonObject ?: return listOf("dns block is missing")
        val servers = dnsServers(dns)
        val dnsTags = servers.mapNotNull(::tagOf).filter { it.isNotBlank() }.toSet()
        val outbounds = outboundObjects(root)
        val problems = serverEdges(servers, dnsTags, outbounds).toMutableList()
        problems += resolverCycles(servers)
        dns["final"]?.jsonPrimitive?.contentOrNull?.let { finalTag ->
            if (finalTag.isNotBlank() && finalTag !in dnsTags) {
                problems += "dns.final[$finalTag] not found"
            }
        }
        (dns["rules"] as? JsonArray)?.forEachIndexed { index, element ->
            val rule = element as? JsonObject ?: return@forEachIndexed
            rule["server"]?.jsonPrimitive?.contentOrNull?.let { server ->
                if (server.isNotBlank() && server !in dnsTags) {
                    problems += "dns.rules[$index].server[$server] not found"
                }
            }
        }
        resolverRef(root["route"] as? JsonObject, "default_domain_resolver")?.let { ref ->
            if (ref !in dnsTags) {
                problems += "route.default_domain_resolver[$ref] not found"
            }
        }
        return problems
    }

    fun resolverRef(server: JsonObject): String? = resolverRef(server, "domain_resolver")
        ?: resolverRef(server, "address_resolver")

    fun resolverRef(owner: JsonObject?, key: String): String? {
        if (owner == null) return null
        return when (val value = owner[key]) {
            is JsonPrimitive -> value.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> value["server"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    fun tagOf(obj: JsonObject): String? =
        obj["tag"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun detourOf(server: JsonObject): String? =
        server["detour"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun serverEdges(
        servers: List<JsonObject>,
        dnsTags: Set<String>,
        outbounds: Map<String, JsonObject>,
    ): List<String> = buildList {
        servers.forEach { server ->
            val tag = tagOf(server) ?: "?"
            resolverRef(server)?.let { ref ->
                if (ref !in dnsTags) add("dependency[$ref] not found for server[$tag]")
            }
            detourOf(server)?.let { detour ->
                addAll(detourProblems(detour, tag, outbounds))
            }
        }
    }

    private fun detourProblems(
        detour: String,
        tag: String,
        outbounds: Map<String, JsonObject>,
    ): List<String> {
        val outbound = outbounds[detour]
        return when {
            outbound == null -> listOf("detour[$detour] not found for server[$tag]")
            isEmptyDirect(outbound) -> listOf("detour[$detour] is an empty direct outbound for server[$tag]")
            else -> emptyList()
        }
    }

    fun isEmptyDirect(outbound: JsonObject): Boolean {
        val type = outbound["type"]?.jsonPrimitive?.contentOrNull
        if (type != "direct") return false
        return NON_EMPTY_DIRECT_KEYS.none { outbound.containsKey(it) }
    }

    fun dnsServers(dns: JsonObject): List<JsonObject> =
        (dns["servers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun outboundObjects(root: JsonObject): Map<String, JsonObject> {
        val objects = (root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return objects.mapNotNull { outbound -> tagOf(outbound)?.let { it to outbound } }.toMap()
    }

    private fun resolverCycles(servers: List<JsonObject>): List<String> {
        val nextOf = mutableMapOf<String, String>()
        servers.forEach { server ->
            val tag = tagOf(server) ?: return@forEach
            val ref = resolverRef(server) ?: return@forEach
            nextOf.putIfAbsent(tag, ref)
        }
        val problems = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        nextOf.keys.forEach { start ->
            if (start in seen) return@forEach
            val stack = mutableListOf<String>()
            val index = mutableMapOf<String, Int>()
            var node: String? = start
            while (node != null && node !in seen) {
                val existing = index[node]
                if (existing != null) {
                    val cycle = stack.subList(existing, stack.size).toList() + node
                    problems += "resolver cycle ${cycle.joinToString("->")}"
                    break
                }
                index[node] = stack.size
                stack += node
                node = nextOf[node]
            }
            seen += stack
        }
        return problems
    }
}
