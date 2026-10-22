package app.lernet.engine.compile

import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.net.LocalProxyAccess
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.engine.RunMode
import app.lernet.routing.CompiledRoute
import app.lernet.routing.GeoRuleSets
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RoutePlatform
import app.lernet.routing.RoutePlatformRules
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AssembledConfig(
    val json: String,
    val proxyTag: String,
    val errors: List<String>,
    val notes: List<String> = emptyList(),
    /** Proxy mode: the only login/password the 127.0.0.1:2080 listener accepts. */
    val userProxy: LocalProxyAccess? = null,
    /** LerNET's own requests: the only login/password the internal app-in listener accepts. */
    val appProxy: LocalProxyAccess? = null,
    /** Folder backups carried only to be checked through this core: outbound id → tag. No rule routes to them. */
    val standbyTags: Map<String, String> = emptyMap(),
) {
    val isValid: Boolean get() = errors.isEmpty()

    /** What LerNET changed in the user's config, for the screen (see [ConfigNotes]). */
    val userChanges: List<String> get() = ConfigNotes.userVisible(notes)
}

object ConfigAssembler {
    const val SNIFF_TIMEOUT = "300ms"
    const val TUN_MTU = 1500
    const val TUN_ADDRESS = "172.19.0.1/30"
    const val WINDOWS_TUN_INTERFACE = "LerNET"
    const val TUN_STACK = "gvisor"
    /** Internal listener for LerNET's own requests (updates, subscriptions, checks). Never without a password. */
    const val APP_PROXY_PORT = 2081
    const val ANDROID_PROBE_URL = "https://cp.cloudflare.com/generate_204"
    /** Same prefix as [app.lernet.engine.live.LiveVia]: standby checks never count as replies of the active config. */
    const val STANDBY_TAG_PREFIX = "standby-"
    const val MAX_STANDBY = 4
    /** Plain proxy outbounds only: nothing that brings its own interface, chain or endpoint. */
    private val STANDBY_TYPES = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls")

    private val json = Json { ignoreUnknownKeys = true }

    fun assemble(
        outbound: NormalizedOutbound,
        compiledRoute: CompiledRoute,
        mode: RunMode,
        logLevel: String,
        dnsJson: String? = null,
        dnsPolicy: DnsPolicy = DnsPolicy.DEFAULT,
        ruleSetDirectory: String = "",
        defaults: EngineDefaults = EngineDefaults(),
        platform: EnginePlatform = EnginePlatform.ANDROID,
        /** Proxy mode only. Null asks for a fresh random login and password. */
        userProxy: LocalProxyAccess? = null,
        /**
         * LerNET's own requests while the connection is on. They always go to the VPN server,
         * whatever the user's rules say. Null: no internal listener at all.
         */
        appProxy: LocalProxyAccess? = null,
        /**
         * Folder backups to carry as extra outbounds, so a backup is checked by a real request through it
         * (a full handshake with its server) while the running connection stays up. Nothing routes to them.
         */
        standby: List<NormalizedOutbound> = emptyList(),
    ): AssembledConfig {
        if (!compiledRoute.isValid) {
            return AssembledConfig(
                json = "",
                proxyTag = outbound.tag,
                errors = compiledRoute.errors.map { "${it.field}: ${it.message}" },
            )
        }
        val outboundObj = runCatching { json.parseToJsonElement(outbound.singBoxJson).jsonObject }
            .getOrElse {
                return AssembledConfig("", outbound.tag, listOf("outbound: повреждённый JSON узла"))
            }
        if (ExternalExitProfiles.INTERFACE_FIELD in outboundObj) {
            return AssembledConfig("", outbound.tag, listOf(
                "Подтверждённый интерфейс Windows используется только в экспертном режиме. " +
                    "Выберите его целью правила в «Экспертном режиме»; обычный VPN этот выход не запускает.",
            ))
        }
        val routingPlatform = if (platform == EnginePlatform.WINDOWS) RoutePlatform.WINDOWS else RoutePlatform.ANDROID
        // Also protect callers that supplied a route compiled without a platform.
        val unsupported = compiledRoute.rules.filter { RoutePlatformRules.unsupported(it.match, routingPlatform) }
        val activeRoute = compiledRoute.copy(rules = compiledRoute.rules - unsupported.toSet())
        val ruleSets = ruleSetEntries(activeRoute, ruleSetDirectory)
        val ruleSetError = ruleSets.error
        if (ruleSetError != null) {
            return AssembledConfig("", outbound.tag, listOf(ruleSetError))
        }
        val taggedRaw = TlsHandshake.apply(
            JsonObject(outboundObj.toMutableMap().apply { put("tag", JsonPrimitive(outbound.tag)) }),
            defaults,
        )
        val xhttp = XhttpMode.normalize(taggedRaw, defaults.xmuxChannels)
        val tagged = xhttp.outbound
        val pipeNames = activeRoute.rules.map { it.pipeName.trim() }.filter { it.isNotEmpty() }.distinct()
        val pipeTags = PipeAffinity.tags(pipeNames, setOf(outbound.tag, "direct"))
        val clones = pipeNames.map { name -> PipeAffinity.clone(taggedRaw, pipeTags.getValue(name), defaults.xmuxChannels) }
        val standbyOutbounds = standbyEntries(standby, outbound, defaults)
        val proxyAccess = if (mode == RunMode.PROXY) userProxy ?: LocalProxyAccess.random(LocalProxyAccess.USER_PORT) else null
        val inbound = inboundFor(mode, defaults, platform, proxyAccess)
        val preparedDns = DnsBlock.prepare(dnsJson, outbound.tag, dnsPolicy, defaults.directDnsServer)
        val dns = preparedDns.dns
        val rules = appRules(appProxy, outbound.tag) + platformRules(mode, platform, outbound.tag) +
            RouteCompiler.toSingBoxRules(activeRoute, outbound.tag, pipeTags) +
            finalReject(activeRoute)
        val finalTag = when (activeRoute.finalAction) {
            RouteAction.PROXY -> outbound.tag
            RouteAction.DIRECT,
            RouteAction.BLOCK,
            -> "direct"
        }
        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", logLevel)
                put("timestamp", true)
            }
            put("dns", dns)
            put("inbounds", buildJsonArray {
                add(inbound)
                if (appProxy != null) add(localListener(LocalListeners.APP_PROXY_TAG, appProxy))
            })
            put("outbounds", outboundArray(tagged, clones.map { it.outbound } + standbyOutbounds.map { it.second }))
            put("route", routeBlock(rules, finalTag, dns, ruleSets.entries))
        }
        val notes = buildList {
            val inactiveCount = (compiledRoute.inactiveNodeIds + unsupported.map { it.nodeId }).size
            if (inactiveCount > 0) add("На этом устройстве неактивны ветки другой платформы: $inactiveCount. Они сохранены для редактирования и экспорта.")
            xhttp.note?.let(::add)
            clones.mapNotNull { it.note }.forEach(::add)
            addAll(preparedDns.notes)
            add(SniffRematch.note(finalTag))
            add("stack=$TUN_STACK")
            if (ruleSets.entries.isNotEmpty()) add("rule-set=${ruleSets.entries.size}")
        }
        val graphProblems = DnsDependency.dangling(root)
        return AssembledConfig(
            json = root.toString(),
            proxyTag = outbound.tag,
            errors = graphProblems,
            notes = notes,
            userProxy = proxyAccess,
            appProxy = appProxy,
            standbyTags = standbyOutbounds.associate { (id, obj) -> id to obj.getValue("tag").jsonPrimitive.content },
        )
    }

    /** True when [json] carries folder backups for checks ([STANDBY_TAG_PREFIX]). */
    fun hasStandby(json: String): Boolean = json.contains("\"$STANDBY_TAG_PREFIX")

    /** [json] without the standby outbounds; nothing routes to them, so nothing else changes. */
    fun withoutStandby(json: String): String {
        if (!hasStandby(json)) return json
        val root = runCatching { this.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return json
        val outbounds = root["outbounds"] as? JsonArray ?: return json
        val kept = outbounds.filterNot { (it as? JsonObject)?.get("tag")?.jsonPrimitive?.content?.startsWith(STANDBY_TAG_PREFIX) == true }
        return JsonObject(root.toMutableMap().apply { put("outbounds", JsonArray(kept)) }).toString()
    }

    private fun standbyEntries(
        standby: List<NormalizedOutbound>,
        active: NormalizedOutbound,
        defaults: EngineDefaults,
    ): List<Pair<String, JsonObject>> = standby
        .filter { it.id != active.id && it.type.lowercase() in STANDBY_TYPES }
        .distinctBy { it.id }
        .take(MAX_STANDBY)
        .mapNotNull { candidate ->
            val obj = runCatching { json.parseToJsonElement(candidate.singBoxJson).jsonObject }.getOrNull() ?: return@mapNotNull null
            if (ExternalExitProfiles.INTERFACE_FIELD in obj || obj["detour"] != null) return@mapNotNull null
            candidate.id to obj
        }
        .mapIndexed { index, (id, obj) ->
            val tag = "$STANDBY_TAG_PREFIX${index + 1}"
            val stamped = TlsHandshake.apply(JsonObject(obj.toMutableMap().apply { put("tag", JsonPrimitive(tag)) }), defaults)
            id to XhttpMode.normalize(stamped, defaults.xmuxChannels).outbound
        }

    private fun ruleSetEntries(route: CompiledRoute, directory: String): RuleSetAttach {
        val tags = GeoRuleSets.tags(route.rules.flatMap { it.match.geoip })
        val missing = GeoRuleSets.missing(route.rules.flatMap { it.match.geoip })
        if (missing.isNotEmpty()) {
            return RuleSetAttach(emptyList(), "Нет набора адресов для страны: ${missing.joinToString()}")
        }
        if (tags.isEmpty()) return RuleSetAttach(emptyList(), null)
        if (directory.isBlank()) return RuleSetAttach(emptyList(), "Каталог наборов стран не задан")
        val root = File(directory)
        val entries = tags.map { tag ->
            buildJsonObject {
                put("type", "local")
                put("tag", tag)
                put("format", "binary")
                put("path", File(root, "$tag.srs").path.replace('\\', '/'))
            }
        }
        return RuleSetAttach(entries, null)
    }

    private fun outboundArray(tagged: JsonObject, pipes: List<JsonObject>): JsonArray =
        buildJsonArray {
            add(tagged)
            pipes.forEach { add(it) }
            // Empty direct (type+tag only). Route rules may select it.
            // DNS servers must not set detour to this tag: lx.8 IsEmpty() rejects it.
            add(
                buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                },
            )
        }

    private fun platformRules(mode: RunMode, platform: EnginePlatform, proxyTag: String): List<JsonObject> =
        buildList {
            // Canonical: sniff without inbound so TCP rematches after timeout.
            // Hijack port 53 then protocol=dns (sing-box#3878). Never mixed.
            add(
                buildJsonObject {
                    put("action", "sniff")
                    put("timeout", SNIFF_TIMEOUT)
                },
            )
            add(
                buildJsonObject {
                    put("port", 53)
                    put("action", "hijack-dns")
                },
            )
            add(
                buildJsonObject {
                    put("protocol", "dns")
                    put("action", "hijack-dns")
                },
            )
            if (mode == RunMode.FULL_VPN) {
                add(
                    buildJsonObject {
                        put("ip_is_private", true)
                        put("outbound", "direct")
                    },
                )
            }
        }

    /** First rule: everything LerNET itself sends goes to the VPN server, before sniffing or user rules. */
    private fun appRules(appProxy: LocalProxyAccess?, proxyTag: String): List<JsonObject> =
        if (appProxy == null) emptyList() else listOf(buildJsonObject {
            put("inbound", buildJsonArray { add(JsonPrimitive(LocalListeners.APP_PROXY_TAG)) })
            put("outbound", proxyTag)
        })

    private fun finalReject(compiledRoute: CompiledRoute): List<JsonObject> =
        if (compiledRoute.finalAction == RouteAction.BLOCK) {
            listOf(buildJsonObject { put("action", "reject") })
        } else {
            emptyList()
        }

    private fun routeBlock(
        rules: List<JsonObject>,
        finalTag: String,
        dns: JsonObject,
        ruleSets: List<JsonObject>,
    ): JsonObject = buildJsonObject {
        put("auto_detect_interface", true)
        DnsBlock.preferredResolverTag(dns)?.let { put("default_domain_resolver", it) }
        if (ruleSets.isNotEmpty()) {
            put("rule_set", buildJsonArray { ruleSets.forEach { add(it) } })
        }
        put("rules", buildJsonArray { rules.forEach { add(it) } })
        put("final", finalTag)
    }

    private fun inboundFor(
        mode: RunMode,
        defaults: EngineDefaults,
        platform: EnginePlatform,
        proxyAccess: LocalProxyAccess?,
    ): JsonObject =
        when (mode) {
            RunMode.FULL_VPN -> buildJsonObject {
                put("type", "tun")
                put("tag", "tun-in")
                // Android supplies an existing fd; Windows needs sing-box to create the adapter.
                if (platform == EnginePlatform.WINDOWS) put("interface_name", WINDOWS_TUN_INTERFACE)
                put(
                    "address",
                    buildJsonArray {
                        add(JsonPrimitive(TUN_ADDRESS))
                        if (platform == EnginePlatform.WINDOWS) add(JsonPrimitive("fdfe:dcba:9876::1/126"))
                    },
                )
                put("mtu", defaults.tunMtu.coerceIn(1280, 9000))
                // Explicit gvisor. Omit defaults mixed (system TCP + gvisor UDP):
                // QUIC/packet alive, TCP dials dead. Never emit mixed.
                put("stack", TUN_STACK)
                put("auto_route", true)
                // Windows: without it DNS can leave through the physical adapter. Android has no use for it.
                put("strict_route", platform == EnginePlatform.WINDOWS && defaults.strictRoute)
            }
            RunMode.PROXY -> localListener(LocalListeners.USER_PROXY_TAG, checkNotNull(proxyAccess))
        }

    /** Loopback SOCKS/HTTP listener that refuses everyone without the login and password. */
    private fun localListener(tag: String, access: LocalProxyAccess): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", tag)
        put("listen", LocalProxyAccess.HOST)
        put("listen_port", access.port)
        put("users", buildJsonArray {
            add(buildJsonObject {
                put("username", access.username)
                put("password", access.password)
            })
        })
    }

    private data class RuleSetAttach(val entries: List<JsonObject>, val error: String?)
}
