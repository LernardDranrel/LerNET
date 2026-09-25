package app.lernet.engine.compile

import app.lernet.config.model.DnsPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PreparedDns(
    val dns: JsonObject,
    val notes: List<String>,
)

data class StampedConfig(
    val json: String,
    val notes: List<String>,
)

object DnsBlock {
    const val STRATEGY_IPV4_ONLY = "ipv4_only"
    const val LOCAL_TAG = "local"
    const val REMOTE_TAG = "dns-remote"
    const val DIRECT_TAG = "dns-direct"
    const val DIRECT_SERVER = "1.1.1.1"
    const val UNDERLAY_CRUMB_PREFIX = "underlay dns final"
    const val PROFILE_KEPT_PREFIX = "dns profile kept"
    private const val PROFILE_REMOTE_TAG = "remote"

    private val json = Json { ignoreUnknownKeys = true }
    private val DIALING_DNS_TYPES = setOf("https", "h3", "tls", "quic", "udp", "tcp")
    private val HOST_PREFIXES = listOf(
        "tls://" to "tls",
        "quic://" to "quic",
        "tcp://" to "tcp",
        "udp://" to "udp",
    )

    fun prepare(
        dnsJson: String?,
        proxyTag: String,
        policy: DnsPolicy = DnsPolicy.UNDERLAY,
        directServer: String = DIRECT_SERVER,
    ): PreparedDns {
        val base = if (dnsJson.isNullOrBlank()) defaultTyped(directServer) else normalize(dnsJson, directServer)
        val sealed = sealDependencies(rewriteDeadOverProxy(base), directServer)
        return when (policy) {
            DnsPolicy.UNDERLAY -> stampUnderlayFinal(sealed, proxyTag, directServer)
            DnsPolicy.PROFILE -> keepProfileDns(sealed)
        }
    }

    fun enforceCompiledRemote(
        compiledJson: String,
        proxyTag: String,
        policy: DnsPolicy = DnsPolicy.UNDERLAY,
    ): StampedConfig {
        val root = parseObject(compiledJson)
        val dns = root?.get("dns") as? JsonObject
        if (root == null || dns == null) {
            val why = if (root == null) "compiled JSON is not an object" else "dns block missing"
            return StampedConfig(compiledJson, listOf("$UNDERLAY_CRUMB_PREFIX skipped: $why"))
        }
        val prepared = prepare(dns.toString(), proxyTag, policy)
        val next = JsonObject(root.toMutableMap().apply { put("dns", prepared.dns) })
        return StampedConfig(next.toString(), prepared.notes)
    }

    fun normalize(dnsJson: String?, directServer: String = DIRECT_SERVER): JsonObject {
        val parsed = parseObject(dnsJson)
        if (parsed != null && serverProblems(parsed["servers"]).isEmpty()) {
            return parsed
        }
        if (parsed != null) {
            return migrate(parsed, directServer)
        }
        return defaultTyped(directServer)
    }

    fun preferredResolverTag(dns: JsonObject): String? {
        val servers = dns["servers"] as? JsonArray ?: return null
        val objects = servers.mapNotNull { it as? JsonObject }
        val direct = objects.firstOrNull { DnsDependency.tagOf(it) == DIRECT_TAG }
            ?: objects.firstOrNull {
                DnsDependency.detourOf(it) == "direct" &&
                    it["type"]?.jsonPrimitive?.contentOrNull != "local"
            }
        val local = objects.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "local" }
        val tagged = direct ?: local ?: objects.firstOrNull()
        return tagged?.get("tag")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    fun defaultTyped(directServer: String = DIRECT_SERVER): JsonObject =
        buildJsonObject {
            put(
                "servers",
                buildJsonArray {
                    add(typedDirectUdp(DIRECT_TAG, directServer))
                    add(typedLocal(LOCAL_TAG))
                },
            )
            put("final", DIRECT_TAG)
            put("strategy", STRATEGY_IPV4_ONLY)
        }

    internal fun rewriteDeadOverProxy(dns: JsonObject): PreparedDns {
        val notes = mutableListOf<String>()
        val servers = dns["servers"] as? JsonArray ?: return PreparedDns(ensureStrategy(dns), emptyList())
        val next = servers.map { element ->
            val server = element as? JsonObject ?: return@map element
            rewriteServer(server)
        }
        val rewritten = JsonObject(
            dns.toMutableMap().apply {
                put("servers", JsonArray(next))
                put("strategy", JsonPrimitive(STRATEGY_IPV4_ONLY))
            },
        )
        return PreparedDns(rewritten, notes)
    }

    internal fun sealDependencies(prepared: PreparedDns, directServer: String = DIRECT_SERVER): PreparedDns {
        val notes = prepared.notes.toMutableList()
        val servers = DnsDependency.dnsServers(prepared.dns).toMutableList()
        if (servers.none { DnsDependency.tagOf(it) == LOCAL_TAG }) {
            servers += typedLocal(LOCAL_TAG)
            notes += "dns added type:local tag=local (resolver for underlay domain_resolver)"
        }
        if (servers.none { DnsDependency.tagOf(it) == DIRECT_TAG }) {
            servers += typedDirectUdp(DIRECT_TAG, directServer)
            notes += "dns added udp $directServer tag=$DIRECT_TAG (underlay dialer, no detour)"
        }
        val dnsTags = servers.mapNotNull { DnsDependency.tagOf(it) }.filter { it.isNotBlank() }.toSet()
        val sealed = servers.map { server -> sealServer(server, dnsTags, notes) }
        val next = JsonObject(
            prepared.dns.toMutableMap().apply {
                put("servers", JsonArray(sealed))
                put("strategy", JsonPrimitive(STRATEGY_IPV4_ONLY))
                val finalTag = this["final"]?.jsonPrimitive?.contentOrNull
                if (finalTag.isNullOrBlank() || finalTag !in dnsTags) {
                    val fallback = if (DIRECT_TAG in dnsTags) DIRECT_TAG else LOCAL_TAG
                    put("final", JsonPrimitive(fallback))
                    if (!finalTag.isNullOrBlank()) {
                        notes += "dns.final remapped $finalTag→$fallback"
                    }
                }
            },
        )
        return PreparedDns(next, notes)
    }

    private fun keepProfileDns(prepared: PreparedDns): PreparedDns {
        val finalTag = prepared.dns["final"]?.jsonPrimitive?.contentOrNull ?: "?"
        return prepared.copy(notes = prepared.notes + "$PROFILE_KEPT_PREFIX final=$finalTag")
    }

    private fun stampUnderlayFinal(prepared: PreparedDns, proxyTag: String, directServer: String): PreparedDns {
        val notes = prepared.notes.toMutableList()
        val dropped = mutableListOf<String>()
        val kept = DnsDependency.dnsServers(prepared.dns).filter { server ->
            if (isUnderlayServer(server)) {
                true
            } else {
                dropped += DnsDependency.tagOf(server) ?: server["type"]?.jsonPrimitive?.contentOrNull ?: "?"
                false
            }
        }.toMutableList()
        if (kept.none { DnsDependency.tagOf(it) == DIRECT_TAG }) {
            kept += typedDirectUdp(DIRECT_TAG, directServer)
            notes += "dns added udp $directServer tag=$DIRECT_TAG (underlay dialer, no detour)"
        }
        if (kept.none { DnsDependency.tagOf(it) == LOCAL_TAG }) {
            kept += typedLocal(LOCAL_TAG)
            notes += "dns added type:local tag=local (resolver for underlay domain_resolver)"
        }
        if (dropped.isNotEmpty()) {
            notes += "dns dropped proxied resolver tag=${dropped.joinToString(",")} " +
                "(xhttp detour=$proxyTag does not finish dns)"
        }
        val keptTags = kept.mapNotNull { DnsDependency.tagOf(it) }.filter { it.isNotBlank() }.toSet()
        val direct = kept.first { DnsDependency.tagOf(it) == DIRECT_TAG }
        notes += underlayCrumb(direct)
        val dns = JsonObject(
            prepared.dns.toMutableMap().apply {
                put("servers", JsonArray(kept))
                put("final", JsonPrimitive(DIRECT_TAG))
                val rules = this["rules"] as? JsonArray
                if (rules != null) put("rules", retargetDnsRules(rules, keptTags))
            },
        )
        return PreparedDns(dns, notes)
    }

    private fun isUnderlayServer(server: JsonObject): Boolean {
        val type = server["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tag = DnsDependency.tagOf(server)
        if (tag == REMOTE_TAG || tag == PROFILE_REMOTE_TAG) return false
        if (type == "local" || type == "dhcp" || type == "fakeip" || tag == DIRECT_TAG) return true
        val detour = DnsDependency.detourOf(server)
        return detour.isNullOrBlank() && type == "udp"
    }

    private fun underlayCrumb(server: JsonObject): String {
        val tag = DnsDependency.tagOf(server) ?: DIRECT_TAG
        val type = server["type"]?.jsonPrimitive?.contentOrNull ?: "?"
        val address = server["server"]?.jsonPrimitive?.contentOrNull ?: "?"
        val detour = server["detour"]?.jsonPrimitive?.contentOrNull ?: "none"
        return "$UNDERLAY_CRUMB_PREFIX tag=$tag type=$type server=$address detour=$detour"
    }

    private fun retargetDnsRules(rules: JsonArray, keptTags: Set<String>): JsonArray =
        JsonArray(
            rules.map { element ->
                val rule = element as? JsonObject ?: return@map element
                val server = rule["server"]?.jsonPrimitive?.contentOrNull
                if (server.isNullOrBlank() || server in keptTags) {
                    rule
                } else {
                    JsonObject(rule.toMutableMap().apply { put("server", JsonPrimitive(DIRECT_TAG)) })
                }
            },
        )

    private fun sealServer(
        server: JsonObject,
        dnsTags: Set<String>,
        notes: MutableList<String>,
    ): JsonObject {
        val type = server["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tag = DnsDependency.tagOf(server)
        val extras = server.toMutableMap().apply {
            remove("type")
            remove("tag")
        }
        when (type) {
            "local", "dhcp", "fakeip" -> sealLocal(extras, tag, notes)
            else -> sealDialing(server, type, tag, extras, dnsTags, notes)
        }
        return buildTyped(type.ifBlank { "udp" }, tag, extras)
    }

    private fun sealLocal(
        extras: MutableMap<String, JsonElement>,
        tag: String?,
        notes: MutableList<String>,
    ) {
        extras.remove("domain_resolver")
        extras.remove("address_resolver")
        dropEmptyDirectDetour(extras, tag, notes)
    }

    private fun sealDialing(
        server: JsonObject,
        type: String,
        tag: String?,
        extras: MutableMap<String, JsonElement>,
        dnsTags: Set<String>,
        notes: MutableList<String>,
    ) {
        val detour = DnsDependency.detourOf(server)
        val underlay = detour == "direct" || tag == DIRECT_TAG || detour.isNullOrBlank()
        val wantResolver = if (underlay) LOCAL_TAG else DIRECT_TAG
        sealResolver(server, tag, extras, dnsTags, wantResolver, type, notes)
        if (underlay) sealUnderlayDetour(extras, tag, notes)
    }

    private fun sealResolver(
        server: JsonObject,
        tag: String?,
        extras: MutableMap<String, JsonElement>,
        dnsTags: Set<String>,
        wantResolver: String,
        type: String,
        notes: MutableList<String>,
    ) {
        val resolver = DnsDependency.resolverRef(server)
        val broken = resolver != null && (resolver !in dnsTags || resolver == tag)
        if (broken && wantResolver != tag && wantResolver in dnsTags) {
            extras["domain_resolver"] = JsonPrimitive(wantResolver)
            notes += "dns remapped domain_resolver $resolver→$wantResolver tag=${tag ?: "?"}"
            return
        }
        if (broken) {
            extras.remove("domain_resolver")
            extras.remove("address_resolver")
            notes += "dns dropped domain_resolver $resolver tag=${tag ?: "?"} (self or missing)"
            return
        }
        if (resolver == null && type in DIALING_DNS_TYPES && wantResolver != tag) {
            extras["domain_resolver"] = JsonPrimitive(wantResolver)
        }
    }

    private fun sealUnderlayDetour(
        extras: MutableMap<String, JsonElement>,
        tag: String?,
        notes: MutableList<String>,
    ) {
        // lx.8 rejects detour onto {"type":"direct","tag":"direct"}.
        // Omitting detour is the typed-DNS equivalent of that empty direct dialer.
        dropEmptyDirectDetour(extras, tag, notes)
        if (tag != DIRECT_TAG) return
        val leftover = (extras["detour"] as? JsonPrimitive)?.contentOrNull
        if (leftover.isNullOrBlank()) return
        extras.remove("detour")
        notes += "dns dropped detour=$leftover tag=$tag (bootstrap uses underlay dialer)"
    }

    fun legacyProblems(compiledJson: String): List<String> {
        val root = runCatching { json.parseToJsonElement(compiledJson).jsonObject }.getOrNull()
            ?: return listOf("compiled JSON is not an object")
        val dns = root["dns"] as? JsonObject ?: return listOf("dns block is missing")
        return serverProblems(dns["servers"])
    }

    fun serverProblems(servers: JsonElement?): List<String> =
        when (servers) {
            null -> listOf("dns.servers is missing")
            is JsonPrimitive -> listOf("dns.servers is a legacy string list")
            is JsonArray -> servers.flatMapIndexed { index, element -> serverProblemsAt(index, element) }
            else -> listOf("dns.servers has an unsupported shape")
        }

    private fun migrate(dns: JsonObject, directServer: String): JsonObject {
        val migrated = when (val servers = dns["servers"]) {
            is JsonArray -> JsonArray(servers.map(::migrateServer))
            is JsonPrimitive -> JsonArray(listOf(migrateServer(servers)))
            else -> defaultTyped(directServer).getValue("servers")
        }
        return JsonObject(dns.toMutableMap().apply { put("servers", migrated) })
    }

    private fun migrateServer(element: JsonElement): JsonObject =
        when (element) {
            is JsonPrimitive -> addressToTyped(address = element.content, tag = null, source = null)
            is JsonObject -> migrateObjectServer(element)
            else -> typedLocal(LOCAL_TAG)
        }

    private fun migrateObjectServer(source: JsonObject): JsonObject {
        val type = source["type"]?.jsonPrimitive?.contentOrNull
        if (!type.isNullOrBlank()) {
            return JsonObject(source.toMutableMap().apply { remove("address") })
        }
        val address = source["address"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tag = source["tag"]?.jsonPrimitive?.contentOrNull
        return addressToTyped(address, tag, source)
    }

    private fun addressToTyped(address: String, tag: String?, source: JsonObject?): JsonObject {
        val extras = (source?.toMutableMap() ?: mutableMapOf()).apply {
            remove("address")
            remove("type")
            remove("server")
            remove("path")
            remove("server_port")
        }
        return localOrSpecial(address, tag, extras)
            ?: httpsFamily(address, tag, extras)
            ?: prefixedHost(address, tag, extras)
            ?: typedHost("udp", tag, address, extras)
    }

    private fun localOrSpecial(
        address: String,
        tag: String?,
        extras: MutableMap<String, JsonElement>,
    ): JsonObject? =
        when {
            address.isBlank() || address == "local" || address.startsWith("local://") ->
                typedLocal(tag ?: LOCAL_TAG, extras)
            address.startsWith("dhcp://") || address == "dhcp" -> buildTyped("dhcp", tag, extras)
            address == "fakeip" || address.startsWith("fakeip://") -> buildTyped("fakeip", tag, extras)
            else -> null
        }

    private fun httpsFamily(
        address: String,
        tag: String?,
        extras: MutableMap<String, JsonElement>,
    ): JsonObject? =
        when {
            address.startsWith("https://") -> typedHttps(tag, address.removePrefix("https://"), extras)
            address.startsWith("h3://") -> typedHttps(tag, address.removePrefix("h3://"), extras, type = "h3")
            else -> null
        }

    private fun prefixedHost(
        address: String,
        tag: String?,
        extras: MutableMap<String, JsonElement>,
    ): JsonObject? {
        val match = HOST_PREFIXES.firstOrNull { address.startsWith(it.first) } ?: return null
        return typedHost(match.second, tag, address.removePrefix(match.first), extras)
    }

    private fun typedLocal(tag: String, extras: MutableMap<String, JsonElement> = mutableMapOf()): JsonObject {
        extras.remove("domain_resolver")
        extras.remove("address_resolver")
        return buildTyped("local", tag, extras)
    }

    private fun typedDirectUdp(tag: String, server: String): JsonObject =
        typedHost(
            "udp",
            tag,
            server,
            mutableMapOf(
                "domain_resolver" to JsonPrimitive(LOCAL_TAG),
            ),
        )

    private fun dropEmptyDirectDetour(
        extras: MutableMap<String, JsonElement>,
        tag: String?,
        notes: MutableList<String>,
    ) {
        val detour = (extras["detour"] as? JsonPrimitive)?.contentOrNull
        if (detour != "direct") return
        extras.remove("detour")
        notes += "dns dropped detour=direct tag=${tag ?: "?"} (lx.8 empty direct outbound)"
    }

    private fun rewriteServer(server: JsonObject): JsonObject {
        val type = server["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val tag = server["tag"]?.jsonPrimitive?.contentOrNull
        if (type == "local" || type == "dhcp" || type == "fakeip") {
            return typedLocal(
                tag ?: LOCAL_TAG,
                server.toMutableMap().apply {
                    remove("type")
                    remove("tag")
                },
            )
        }
        return server
    }

    private fun ensureStrategy(dns: JsonObject): JsonObject =
        JsonObject(dns.toMutableMap().apply { put("strategy", JsonPrimitive(STRATEGY_IPV4_ONLY)) })

    private fun typedHost(
        type: String,
        tag: String?,
        hostPort: String,
        extras: MutableMap<String, JsonElement>,
    ): JsonObject {
        val (host, port) = splitHostPort(hostPort)
        extras["server"] = JsonPrimitive(host)
        if (port != null) extras["server_port"] = JsonPrimitive(port)
        return buildTyped(type, tag, extras)
    }

    private fun typedHttps(
        tag: String?,
        remainder: String,
        extras: MutableMap<String, JsonElement>,
        type: String = "https",
    ): JsonObject {
        val pathStart = remainder.indexOf('/')
        val hostPort = if (pathStart >= 0) remainder.substring(0, pathStart) else remainder
        val path = if (pathStart >= 0) remainder.substring(pathStart) else null
        val (host, port) = splitHostPort(hostPort)
        extras["server"] = JsonPrimitive(host)
        if (port != null) extras["server_port"] = JsonPrimitive(port)
        if (!path.isNullOrBlank() && path != "/") extras["path"] = JsonPrimitive(path)
        return buildTyped(type, tag, extras)
    }

    private fun buildTyped(type: String, tag: String?, extras: MutableMap<String, JsonElement>): JsonObject =
        buildJsonObject {
            put("type", type)
            if (!tag.isNullOrBlank()) put("tag", tag)
            extras.forEach { (key, value) ->
                if (key != "type" && key != "tag") put(key, value)
            }
        }

    private fun splitHostPort(raw: String): Pair<String, Int?> {
        val value = raw.trim().trim('[', ']')
        val colon = value.lastIndexOf(':')
        if (colon <= 0) return value to null
        val port = value.substring(colon + 1).toIntOrNull() ?: return value to null
        return value.substring(0, colon).trim('[', ']') to port
    }

    private fun parseObject(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    }

    private fun serverProblemsAt(index: Int, element: JsonElement): List<String> =
        when (element) {
            is JsonPrimitive -> listOf("dns.servers[$index] is a legacy string")
            is JsonObject -> {
                val type = element["type"]?.jsonPrimitive?.contentOrNull
                buildList {
                    if (type.isNullOrBlank()) add("dns.servers[$index] missing type")
                    if (element.containsKey("address")) add("dns.servers[$index] uses legacy address")
                }
            }
            else -> listOf("dns.servers[$index] is not an object")
        }
}
