package app.lernet.engine.compile

import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.redact.SecretRedactor
import app.lernet.engine.RunMode
import app.lernet.routing.CompiledRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class TruthFieldId {
    SERVER,
    PORT,
    SNI,
    REALITY,
    DNS,
    TRANSPORT_MODE,
    XMUX,
    TUN_STACK,
    TUN_MTU,
    LOG_LEVEL,
    ROUTE_PREFIX,
}

enum class FieldSource {
    PROFILE,
    GLOBAL,
    SYSTEM,
}

enum class OverrideReason {
    DNS_DROPPED_PROXIED,
    DNS_DROPPED_DIRECT_DETOUR,
    XMUX_STAMPED,
    LOG_RAISED,
}

data class FieldView(
    val id: TruthFieldId,
    val profileValue: String,
    val globalValue: String?,
    val effectiveValue: String,
    val source: FieldSource,
    val overridden: Boolean,
    val reason: OverrideReason?,
    val reasonDetail: String?,
)

data class ConfigPreview(
    val fields: List<FieldView>,
    val engineJson: String,
    val errors: List<String>,
    val fullEngineJson: String = "",
)

object ConfigTruth {
    const val ROUTE_PREFIX_EFFECTIVE = "sniff 300ms; port 53 hijack-dns; protocol dns hijack-dns"

    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
    private val proxiedTypes = setOf("https", "tls", "tcp", "quic", "h3")

    fun preview(
        outbound: NormalizedOutbound,
        route: CompiledRoute,
        mode: RunMode,
        logLevel: String,
        dnsJson: String?,
        dnsPolicy: DnsPolicy,
        defaults: EngineDefaults = EngineDefaults(),
        ruleSetDirectory: String = "",
    ): ConfigPreview {
        val effectiveLog = if (logLevel == "warn" || logLevel == "error") "info" else logLevel
        val assembled = ConfigAssembler.assemble(
            outbound = outbound,
            compiledRoute = route,
            mode = mode,
            logLevel = effectiveLog,
            dnsJson = dnsJson,
            dnsPolicy = dnsPolicy,
            defaults = defaults,
            ruleSetDirectory = ruleSetDirectory,
        )
        val input = TruthInput(
            outboundJson = outbound.singBoxJson,
            dnsJson = dnsJson,
            dnsPolicy = dnsPolicy,
            logLevel = logLevel,
            assembledJson = assembled.json,
            notes = assembled.notes,
            defaults = defaults,
        )
        return ConfigPreview(
            fields = fields(input),
            engineJson = redactedEngineJson(assembled.json),
            errors = assembled.errors,
            fullEngineJson = prettyEngineJson(assembled.json),
        )
    }

    fun fields(input: TruthInput): List<FieldView> {
        val owned = OutboundPatch.read(input.outboundJson)
        val assembledOutbound = proxyOutbound(input.assembledJson)
        return listOf(
            textField(TruthFieldId.SERVER, owned.server, text(assembledOutbound, "server")),
            textField(TruthFieldId.PORT, owned.port, text(assembledOutbound, "server_port")),
            textField(TruthFieldId.SNI, owned.sni, sniOf(assembledOutbound)),
            realityField(owned),
            dnsField(input),
            modeField(owned, assembledOutbound),
            xmuxField(input, owned),
            systemPlain(TruthFieldId.TUN_STACK, ConfigAssembler.TUN_STACK),
            globalPlain(TruthFieldId.TUN_MTU, input.defaults.tunMtu.toString()),
            logField(input.logLevel),
            systemPlain(TruthFieldId.ROUTE_PREFIX, ROUTE_PREFIX_EFFECTIVE),
        )
    }

    fun redactedEngineJson(raw: String): String {
        if (raw.isBlank()) return ""
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
            ?: return SecretRedactor.redact(raw)
        return SecretRedactor.redact(pretty.encodeToString(JsonElement.serializer(), element))
    }

    private fun dnsField(input: TruthInput): FieldView {
        val profileValue = profileDnsSummary(input.dnsJson)
        val effective = assembledDns(input.assembledJson)?.let { formatDns(it) }.orEmpty()
        val dropped = input.notes.firstOrNull { it.contains("dns dropped proxied resolver") }
        val directDetour = input.notes.firstOrNull { it.contains("dns dropped detour=direct") }
        val proxiedDrop = input.dnsPolicy == DnsPolicy.UNDERLAY && (dropped != null || profileLooksProxied(input.dnsJson))
        val reason = when {
            proxiedDrop -> OverrideReason.DNS_DROPPED_PROXIED
            directDetour != null -> OverrideReason.DNS_DROPPED_DIRECT_DETOUR
            else -> null
        }
        val detail = when (reason) {
            OverrideReason.DNS_DROPPED_PROXIED -> dropped
            OverrideReason.DNS_DROPPED_DIRECT_DETOUR -> directDetour
            OverrideReason.XMUX_STAMPED,
            OverrideReason.LOG_RAISED,
            null,
            -> null
        }
        return FieldView(
            id = TruthFieldId.DNS,
            profileValue = profileValue,
            globalValue = if (input.dnsJson.isNullOrBlank()) input.defaults.directDnsServer else null,
            effectiveValue = effective,
            source = when {
                reason != null -> FieldSource.SYSTEM
                input.dnsJson.isNullOrBlank() -> FieldSource.GLOBAL
                else -> FieldSource.PROFILE
            },
            overridden = reason != null,
            reason = reason,
            reasonDetail = detail,
        )
    }

    private fun xmuxField(input: TruthInput, owned: OwnedOutbound): FieldView {
        val note = input.notes.firstOrNull { it.contains("xmux max_concurrency=") }
        val stamped = note != null
        val profileValue = owned.xmuxConcurrency
        val effective = if (stamped) input.defaults.xmuxConcurrency else profileValue
        return FieldView(
            id = TruthFieldId.XMUX,
            profileValue = profileValue,
            globalValue = if (stamped) input.defaults.xmuxConcurrency else null,
            effectiveValue = effective,
            source = if (stamped) FieldSource.GLOBAL else FieldSource.PROFILE,
            overridden = false,
            reason = if (stamped) OverrideReason.XMUX_STAMPED else null,
            reasonDetail = note,
        )
    }

    private fun logField(logLevel: String): FieldView {
        val effective = if (logLevel == "warn" || logLevel == "error") "info" else logLevel
        val raised = effective != logLevel
        return FieldView(
            id = TruthFieldId.LOG_LEVEL,
            profileValue = "",
            globalValue = logLevel,
            effectiveValue = effective,
            source = if (raised) FieldSource.SYSTEM else FieldSource.GLOBAL,
            overridden = raised,
            reason = if (raised) OverrideReason.LOG_RAISED else null,
            reasonDetail = null,
        )
    }

    private fun modeField(owned: OwnedOutbound, assembled: JsonObject?): FieldView {
        val transport = assembled?.get("transport") as? JsonObject
        val effective = transport?.let { text(it, "mode") }?.ifBlank { owned.mode } ?: owned.mode
        val changed = owned.mode.isNotBlank() && effective != owned.mode
        return FieldView(
            id = TruthFieldId.TRANSPORT_MODE,
            profileValue = owned.mode,
            globalValue = null,
            effectiveValue = effective,
            source = if (changed) FieldSource.SYSTEM else FieldSource.PROFILE,
            overridden = changed,
            reason = null,
            reasonDetail = null,
        )
    }

    private fun realityField(owned: OwnedOutbound): FieldView =
        textField(TruthFieldId.REALITY, owned.reality, owned.reality)

    private fun textField(id: TruthFieldId, profile: String, effectiveRaw: String): FieldView {
        val effective = effectiveRaw.ifBlank { profile }
        val changed = profile.isNotBlank() && effective != profile
        return FieldView(
            id = id,
            profileValue = profile,
            globalValue = null,
            effectiveValue = effective,
            source = if (changed) FieldSource.SYSTEM else FieldSource.PROFILE,
            overridden = changed,
            reason = null,
            reasonDetail = null,
        )
    }

    private fun systemPlain(id: TruthFieldId, effective: String): FieldView =
        FieldView(
            id = id,
            profileValue = "",
            globalValue = null,
            effectiveValue = effective,
            source = FieldSource.SYSTEM,
            overridden = false,
            reason = null,
            reasonDetail = null,
        )

    private fun globalPlain(id: TruthFieldId, effective: String): FieldView =
        FieldView(id, "", effective, effective, FieldSource.GLOBAL, false, null, null)

    fun prettyEngineJson(raw: String): String =
        runCatching { pretty.encodeToString(JsonElement.serializer(), json.parseToJsonElement(raw)) }
            .getOrDefault(raw)

    private fun profileLooksProxied(dnsJson: String?): Boolean {
        if (dnsJson.isNullOrBlank()) return false
        val servers = DnsBlock.normalize(dnsJson)["servers"] as? JsonArray ?: return false
        return servers.any { element ->
            val server = element as? JsonObject ?: return@any false
            isProxiedServer(server)
        }
    }

    private fun isProxiedServer(server: JsonObject): Boolean {
        val type = text(server, "type")
        val tag = text(server, "tag")
        val detour = text(server, "detour")
        if (tag == DnsBlock.REMOTE_TAG || tag == "remote") return true
        if (type in proxiedTypes) return true
        return detour.isNotBlank() && detour != "direct"
    }

    private fun profileDnsSummary(dnsJson: String?): String {
        if (dnsJson.isNullOrBlank()) return ""
        return serverLines(DnsBlock.normalize(dnsJson))
    }

    private fun formatDns(dns: JsonObject): String {
        val finalTag = text(dns, "final").ifBlank { "?" }
        return "final=$finalTag; ${serverLines(dns)}"
    }

    private fun serverLines(dns: JsonObject): String {
        val servers = dns["servers"] as? JsonArray ?: return ""
        return servers.mapNotNull { it as? JsonObject }.joinToString("; ") { serverLine(it) }
    }

    private fun serverLine(server: JsonObject): String {
        val type = text(server, "type").ifBlank { "?" }
        val tag = text(server, "tag")
        val host = text(server, "server")
        val detour = text(server, "detour")
        return buildString {
            append(type)
            if (tag.isNotBlank()) append(' ').append(tag)
            if (host.isNotBlank()) append(' ').append(host)
            if (detour.isNotBlank()) append(" detour=").append(detour)
        }
    }

    private fun assembledDns(raw: String): JsonObject? = root(raw)?.get("dns") as? JsonObject

    private fun proxyOutbound(raw: String): JsonObject? {
        val servers = root(raw)?.get("outbounds") as? JsonArray ?: return null
        return servers.mapNotNull { it as? JsonObject }.firstOrNull { text(it, "tag") == "proxy" }
    }

    private fun sniOf(outbound: JsonObject?): String {
        val tls = outbound?.get("tls") as? JsonObject ?: return ""
        return text(tls, "server_name")
    }

    private fun root(raw: String): JsonObject? {
        if (raw.isBlank()) return null
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private fun text(obj: JsonObject?, key: String): String =
        obj?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
}

data class TruthInput(
    val outboundJson: String,
    val dnsJson: String?,
    val dnsPolicy: DnsPolicy,
    val logLevel: String,
    val assembledJson: String,
    val notes: List<String>,
    val defaults: EngineDefaults = EngineDefaults(),
)
