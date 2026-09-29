package app.lernet.engine.compile

import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.NormalizedOutbound
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class AssembledConfig(
    val json: String,
    val proxyTag: String,
    val errors: List<String>,
    val notes: List<String> = emptyList(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ConfigAssembler {
    const val SNIFF_TIMEOUT = "300ms"
    const val TUN_MTU = 1500
    const val TUN_ADDRESS = "172.19.0.1/30"
    const val TUN_STACK = "gvisor"
    const val ANDROID_PROBE_PORT = 2081
    const val ANDROID_PROBE_HOST = "cp.cloudflare.com"
    const val ANDROID_PROBE_URL = "https://cp.cloudflare.com/generate_204"

    private val json = Json { ignoreUnknownKeys = true }

    fun assemble(
        outbound: NormalizedOutbound,
        compiledRoute: CompiledRoute,
        mode: RunMode,
        logLevel: String,
        dnsJson: String? = null,
        dnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY,
        ruleSetDirectory: String = "",
        defaults: EngineDefaults = EngineDefaults(),
        platform: EnginePlatform = EnginePlatform.ANDROID,
    ): AssembledConfig {
        if (!compiledRoute.isValid) {
            return AssembledConfig(
                json = "",
                proxyTag = outbound.tag,
                errors = compiledRoute.errors.map { "${it.field}: ${it.message}" },
            )
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
        val outboundObj = runCatching { json.parseToJsonElement(outbound.singBoxJson).jsonObject }
            .getOrElse {
                return AssembledConfig("", outbound.tag, listOf("outbound: повреждённый JSON узла"))
            }
        val taggedRaw = JsonObject(outboundObj.toMutableMap().apply { put("tag", JsonPrimitive(outbound.tag)) })
        val xhttp = XhttpMode.normalize(taggedRaw, defaults.xmuxConcurrency)
        val tagged = xhttp.outbound
        val pipeNames = activeRoute.rules.map { it.pipeName.trim() }.filter { it.isNotEmpty() }.distinct()
        val pipeTags = PipeAffinity.tags(pipeNames, setOf(outbound.tag, "direct"))
        val clones = pipeNames.map { name -> PipeAffinity.clone(taggedRaw, pipeTags.getValue(name), defaults.xmuxConcurrency) }
        val inbound = inboundFor(mode, defaults, platform)
        val preparedDns = DnsBlock.prepare(dnsJson, outbound.tag, dnsPolicy, defaults.directDnsServer)
        val dns = preparedDns.dns
        val rules = platformRules(mode, platform, outbound.tag) +
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
                if (platform == EnginePlatform.ANDROID) add(androidProbeInbound())
            })
            put("outbounds", outboundArray(tagged, clones.map { it.outbound }))
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
        )
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
            if (platform == EnginePlatform.ANDROID) {
                // Permit only this health endpoint to bypass user rules via the loopback probe listener.
                add(buildJsonObject {
                    put("inbound", buildJsonArray { add(JsonPrimitive("probe-in")) })
                    put("domain", buildJsonArray { add(JsonPrimitive(ANDROID_PROBE_HOST)) })
                    put("port", buildJsonArray { add(JsonPrimitive(443)) })
                    put("outbound", proxyTag)
                })
                add(buildJsonObject {
                    put("inbound", buildJsonArray { add(JsonPrimitive("probe-in")) })
                    put("action", "reject")
                })
            }
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

    private fun androidProbeInbound(): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", "probe-in")
        put("listen", "127.0.0.1")
        put("listen_port", ANDROID_PROBE_PORT)
    }

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

    private fun inboundFor(mode: RunMode, defaults: EngineDefaults, platform: EnginePlatform): JsonObject =
        when (mode) {
            RunMode.FULL_VPN -> buildJsonObject {
                put("type", "tun")
                put("tag", "tun-in")
                // Android supplies an existing fd; Windows needs sing-box to create the adapter.
                if (platform == EnginePlatform.WINDOWS) put("interface_name", "LerNET")
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
                put("strict_route", false)
            }
            RunMode.PROXY -> buildJsonObject {
                put("type", "mixed")
                put("tag", "mixed-in")
                put("listen", "127.0.0.1")
                put("listen_port", 2080)
            }
        }

    private data class RuleSetAttach(val entries: List<JsonObject>, val error: String?)
}
