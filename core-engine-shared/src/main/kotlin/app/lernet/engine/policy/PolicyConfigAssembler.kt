package app.lernet.engine.policy

import app.lernet.config.model.DnsPolicy
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.policy.PolicyWorkspace
import app.lernet.config.policy.PolicyWorkspaceCodec
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.compile.DnsBlock
import app.lernet.engine.compile.DnsDependency
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.EnginePlatform
import app.lernet.engine.compile.XhttpMode
import app.lernet.routing.ConditionJson
import app.lernet.routing.GeoRuleSets
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import app.lernet.routing.policy.PolicyDestinationAddress
import app.lernet.routing.policy.PolicyDnsMode
import app.lernet.routing.policy.PolicyExit
import app.lernet.routing.policy.PolicyProgram
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.ProgramRule
import app.lernet.routing.policy.UnavailableFallback
import app.lernet.routing.policy.canonicalChannelPath
import java.io.File
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class PolicyPhysicalExit(
    val tag: String,
    val profileId: String,
    val channelPath: List<String>,
    val lifecycle: ExitLifecyclePolicy,
    val folderId: String? = null,
) {
    val folderIds: Set<String> get() = folderId?.let(::setOf).orEmpty()
    val key: ExpertExitKey get() = ExpertExitKey(profileId, channelPath.lastOrNull(), channelPath, folderId)
}

data class PolicyFolderExit(
    val tag: String,
    val folderId: String,
    val channelPath: List<String>,
    val candidateTags: List<String>,
    val preferredTag: String?,
    val policy: FolderPolicy,
    val lifecycle: ExitLifecyclePolicy,
)

/** Config strings contain credentials. Diagnostics deliberately never stringify their contents. */
class PolicyAssembledConfig(
    val json: String,
    val ingressJson: String,
    val policyJson: String,
    val exitManifestJson: String,
    val program: PolicyProgram,
    val exits: List<PolicyPhysicalExit>,
    val errors: List<String>,
    val notes: List<String> = emptyList(),
    val folders: List<PolicyFolderExit> = emptyList(),
) {
    val manifestJson: String get() = exitManifestJson
    val isValid: Boolean get() = errors.isEmpty()
    val exitTags: Map<ExpertExitKey, String> get() = exits.associate { it.key to it.tag }
    val dnsSafetyErrors: List<String> get() = errors.filter { it.startsWith("DNS:") }
    override fun toString(): String = "PolicyAssembledConfig(revision=${program.revision}, exits=${exits.size}, errors=${errors.size})"
}

/** Pure adapter. Policy generations contain no TUN, listener, service or mutable platform state. */
object PolicyConfigAssembler {
    const val DIRECT_TAG = "direct"
    const val BOOTSTRAP_DNS_TAG = "dns-bootstrap"
    const val DIRECT_DNS_TAG = "dns-direct"
    const val IPV6_TUN_ADDRESS = "fdfe:dcba:9876::1/126"
    private val json = Json
    private val DNS_ACTION_FIELDS = setOf(
        "action", "server", "strategy", "disable_cache", "disable_optimistic_cache", "rewrite_ttl", "timeout",
        "client_subnet", "remove_client_subnet", "method", "no_drop", "rcode", "answer", "ns", "extra",
        "race", "speculative", "tag",
    )

    fun assemble(
        workspace: PolicyWorkspace,
        platform: EnginePlatform,
        folderSelections: Map<String, String> = emptyMap(),
        defaults: EngineDefaults = EngineDefaults(),
        ruleSetDirectory: String = "",
        underlayInterface: String? = null,
        unavailableProfileIds: Set<String> = emptySet(),
        probeSchedule: ExpertProbeSchedule? = null,
        probeUrl: String = ConfigAssembler.ANDROID_PROBE_URL,
    ): PolicyAssembledConfig = assemble(
        workspace,
        PolicyProgramCompiler.compile(
            workspace.saved, PolicyMigration.inventory(workspace.legacy),
            if (platform == EnginePlatform.WINDOWS) RoutePlatform.WINDOWS else RoutePlatform.ANDROID,
        ),
        platform, folderSelections, defaults, ruleSetDirectory, underlayInterface, unavailableProfileIds, probeSchedule, probeUrl,
    )

    fun assemble(
        workspace: PolicyWorkspace,
        program: PolicyProgram,
        platform: EnginePlatform,
        folderSelections: Map<String, String> = emptyMap(),
        defaults: EngineDefaults = EngineDefaults(),
        ruleSetDirectory: String = "",
        underlayInterface: String? = null,
        unavailableProfileIds: Set<String> = emptySet(),
        probeSchedule: ExpertProbeSchedule? = null,
        probeUrl: String = ConfigAssembler.ANDROID_PROBE_URL,
    ): PolicyAssembledConfig {
        if (!program.isValid) return invalid(program, program.errors.map { it.message })
        return try {
            PolicyWorkspaceCodec.validate(workspace)
            require(program.revision == workspace.saved.revision) { "Версия программы не совпадает с сохранённой схемой" }
            require(underlayInterface == null || underlayInterface.isNotBlank() && underlayInterface.none { it.isISOControl() }) {
                "Некорректное имя физического адаптера"
            }
            if (!validProbeUrl(probeUrl)) throw AssemblyProblem("Адрес проверки должен быть HTTPS без учётных данных и фрагмента")
            Builder(
                workspace, program, platform, folderSelections, defaults, ruleSetDirectory, underlayInterface,
                unavailableProfileIds, probeSchedule ?: ExpertProbeSchedule.fromHealth(workspace.saved.health), probeUrl
            ).build()
        } catch (failure: AssemblyProblem) {
            invalid(program, listOf(failure.message ?: "Не удалось собрать схему"))
        } catch (_: Exception) {
            // JSON parsers can include raw credentials in their exception text.
            invalid(program, listOf("Не удалось собрать схему: проверьте формат профилей и параметры выхода"))
        }
    }

    private fun invalid(program: PolicyProgram, errors: List<String>) =
        PolicyAssembledConfig("", "", "", "", program, emptyList(), errors)

    private class Builder(
        val workspace: PolicyWorkspace,
        val program: PolicyProgram,
        val platform: EnginePlatform,
        val selections: Map<String, String>,
        val defaults: EngineDefaults,
        val directory: String,
        val underlay: String?,
        val unavailable: Set<String>,
        val probeSchedule: ExpertProbeSchedule,
        val probeUrl: String,
    ) {
        private val profiles = workspace.legacy.profiles.associateBy { it.id }
        private val folders = workspace.legacy.groups.associateBy { it.id }
        private val channels = workspace.saved.channels.associateBy { it.id }
        private val folderPolicies = workspace.saved.folderPolicies.associateBy { it.folderId }
        private val profilePolicies = workspace.saved.profilePolicies.associateBy { it.profileId }
        private val physical = linkedMapOf<String, PolicyPhysicalExit>()
        private val folderExits = linkedMapOf<String, PolicyFolderExit>()
        private val outbounds = linkedMapOf<String, JsonObject>()
        private val dnsServers = linkedMapOf<String, JsonObject>()
        private val dnsRules = mutableListOf<JsonObject>()
        private val notes = linkedSetOf<String>()
        private val outboundTagsByExit = mutableMapOf<String, Map<String, String>>()
        private val dnsBindings = mutableMapOf<String, DnsBinding>()
        private val unproxiedExitTags = mutableSetOf<String>()
        private val tcpOnlyExitTags = mutableSetOf<String>()

        fun build(): PolicyAssembledConfig {
            outbounds[DIRECT_TAG] = buildJsonObject {
                put("type", "direct")
                put("tag", DIRECT_TAG)
                if (platform == EnginePlatform.WINDOWS) put("lernet_system_route", true)
            }
            dnsServers[BOOTSTRAP_DNS_TAG] = buildJsonObject {
                put("type", "local")
                put("tag", BOOTSTRAP_DNS_TAG)
                if (platform == EnginePlatform.WINDOWS) put("lernet_preserve_destination", true)
            }
            dnsServers[DIRECT_DNS_TAG] = buildJsonObject {
                put("tag", DIRECT_DNS_TAG)
                if (workspace.saved.dns.mode == PolicyDnsMode.CUSTOM) {
                    put("type", "udp")
                    put("server", workspace.saved.dns.server)
                    if (platform == EnginePlatform.WINDOWS) put("lernet_system_route", true)
                } else {
                    put("type", "local")
                    if (platform == EnginePlatform.WINDOWS) put("lernet_preserve_destination", true)
                }
            }
            notes += if (workspace.saved.dns.mode == PolicyDnsMode.SYSTEM) {
                "DNS прямого трафика: исходная сеть; публичный сервер автоматически не подставляется."
            } else {
                "DNS прямого трафика: ${workspace.saved.dns.server}, выбран в настройках Expert. Резервная подмена отключена."
            }
            notes += "Адреса VPN-серверов разрешаются через исходную сеть; защищённые ветки используют DNS своего выхода."
            val rules = mutableListOf<JsonObject>()
            // Reading a ClientHello acknowledges the local TCP handshake before an external
            // route is known. A transparent policy needs no payload to choose its only exit.
            val transparentDirect = program.rules.isNotEmpty() &&
                program.rules.all {
                    it.condition.isEmpty() && it.target.target == PolicyTarget.Direct && !it.protected && it.redirect == null
                }
            if (!transparentDirect) {
                rules += buildJsonObject {
                    put("action", "sniff")
                    put("timeout", ConfigAssembler.SNIFF_TIMEOUT)
                }
            }
            val guardedOwner = program.rules.any {
                (it.protected || it.target.target == PolicyTarget.Block) && ownerPredicate(it.condition)
            }
            val safeUnknownDns = guardedOwner && platform == EnginePlatform.ANDROID
            val ownerGuard = if (guardedOwner) {
                buildJsonObject {
                    val key = if (platform == EnginePlatform.ANDROID) "package_name_regex" else "process_path_regex"
                    put(key, JsonArray(listOf(JsonPrimitive(".+"))))
                    put("invert", true)
                    put("action", "reject")
                }
            } else {
                null
            }
            if (ownerGuard != null) {
                if (!safeUnknownDns) rules += ownerGuard
                notes += "Защита приложений включена: соединение блокируется, если не удалось определить его владельца."
            }
            rules += buildJsonObject {
                put("port", 53)
                put("action", "hijack-dns")
            }
            rules += buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            }
            if (safeUnknownDns) rules += requireNotNull(ownerGuard)
            val unknownDnsRules = mutableListOf<JsonObject>()
            program.rules.forEach { rule ->
                val selected = resolve(rule.target)
                rules += routeRule(rule, selected)
                val projectedRules = dnsRulesFor(rule, selected)
                dnsRules += projectedRules
                if (safeUnknownDns && (rule.protected || rule.target.target == PolicyTarget.Block)) {
                    unknownDnsRules += projectedRules.map { projected ->
                        val condition = JsonObject(projected.filterKeys { it !in DNS_ACTION_FIELDS })
                        buildJsonObject {
                            put("type", "logical")
                            put("mode", "and")
                            put("rules", JsonArray(listOf(unknownPackageCondition(), condition).filter { it.isNotEmpty() }))
                            projected.filterKeys { it in DNS_ACTION_FIELDS }.forEach { (key, value) -> put(key, value) }
                        }
                    }
                }
            }
            if (safeUnknownDns) {
                unknownDnsRules += JsonObject(unknownPackageCondition() + ("action" to JsonPrimitive("reject")))
                dnsRules.addAll(0, unknownDnsRules)
                notes += "DNS без известного владельца проходит только через защищённые выходы; остальные такие запросы блокируются."
            }
            if (dnsRules.any { containsField(it, setOf("ip_version", "query_type")) } &&
                dnsRules.any { containsField(it, setOf("strategy")) }
            ) {
                throw AssemblyProblem(
                    "DNS: старый параметр strategy нельзя сочетать с query_type или ip_version. " +
                        "Уберите strategy из настроек DNS профиля."
                )
            }
            val dns = buildJsonObject {
                put("servers", JsonArray(dnsServers.values.toList()))
                put("rules", JsonArray(dnsRules))
                put("final", DIRECT_DNS_TAG)
                // Windows can select different upstreams for the same name (NRPT/multiple adapters).
                // The OS keeps its own cache; never mix those answers in a generation-wide cache.
                if (platform == EnginePlatform.WINDOWS) put("disable_cache", true)
                put("timeout", "30s")
                put("reverse_mapping", true)
            }
            val route = buildJsonObject {
                put("auto_detect_interface", platform == EnginePlatform.WINDOWS && underlay == null)
                underlay?.let { put("default_interface", it) }
                if (platform == EnginePlatform.WINDOWS) put("find_process", true)
                if (guardedOwner) put("lernet_owner_guard", if (platform == EnginePlatform.ANDROID) "package" else "process")
                if (safeUnknownDns) put("lernet_unknown_owner_dns_safe", true)
                put("default_domain_resolver", BOOTSTRAP_DNS_TAG)
                put("rules", JsonArray(rules))
                put("final", DIRECT_TAG)
                val sets = collectRuleSets(program.rules.map { it.condition } + dnsRules)
                if (sets.isNotEmpty()) put("rule_set", JsonArray(sets))
            }
            val policy = buildJsonObject {
                put("dns", dns)
                put("route", route)
                put("outbounds", JsonArray(outbounds.values.toList()))
                putJsonObject("log") {
                    put("level", "info")
                    put("timestamp", true)
                }
            }
            val ingress = ingress(platform, defaults, underlay)
            val combined = JsonObject(policy + mapOf("inbounds" to ingress.getValue("inbounds")))
            val errors = DnsDependency.dangling(policy).map { "DNS: $it" }
            if (program.inactiveNodeIds.isNotEmpty()) {
                notes += "Ветки другой платформы сохранены, но не исполняются: ${program.inactiveNodeIds.size}."
            }
            if (program.inactiveProtections.isNotEmpty()) {
                notes += "Защита веток другой платформы здесь не действует. " +
                    "Добавьте условия приложений текущего устройства: ${program.inactiveProtections.size}."
            }
            if (platform == EnginePlatform.ANDROID && program.unavailableExitProfileIds.isNotEmpty()) {
                notes += ExternalExitProfiles.ANDROID_INTERFACE_UNSUPPORTED
            }
            val manifest = manifest(physical.values.toList(), folderExits.values.toList(), guardedOwner, probeSchedule, probeUrl)
            return if (errors.isEmpty()) {
                PolicyAssembledConfig(
                    combined.toString(), ingress.toString(), policy.toString(), manifest.toString(),
                    program, physical.values.toList(), emptyList(), notes.toList(), folderExits.values.toList(),
                )
            } else {
                invalid(program, errors)
            }
        }

        private fun resolve(exit: PolicyExit): Resolution {
            val channelPath = exit.physicalChannelPath
            return when (val target = exit.target) {
                PolicyTarget.Direct -> Resolution(DIRECT_TAG)
                PolicyTarget.Block -> Resolution(null)
                PolicyTarget.CurrentExit, is PolicyTarget.Channel -> throw AssemblyProblem("Не раскрыта ссылка на выход схемы")
                is PolicyTarget.Profile -> {
                    if (unsupportedInterface(target.id)) {
                        Resolution(null)
                    } else if (target.id in unavailable) {
                        fallback(target.fallback)
                    } else {
                        profile(target.id, channelPath)
                    }
                }
                is PolicyTarget.Folder -> {
                    val members = folders[target.id]?.profileIds ?: throw AssemblyProblem("Папка выхода удалена")
                    val folderPolicy = folderPolicies[target.id] ?: FolderPolicy(target.id)
                    val requested = selections[target.id]
                    if (requested != null && requested !in members) throw AssemblyProblem("Выбранный выход не принадлежит папке")
                    val preferred = requested ?: folderPolicy.preferredProfileId ?: members.firstOrNull()
                    if (!folderPolicy.autoSwap &&
                        folderPolicy.selection == FolderSelection.PREFERRED &&
                        preferred != null &&
                        unsupportedInterface(preferred)
                    ) {
                        return Resolution(null)
                    }
                    if (!folderPolicy.autoSwap &&
                        folderPolicy.selection == FolderSelection.PREFERRED &&
                        preferred != null &&
                        preferred in unavailable
                    ) {
                        return fallback(target.fallback)
                    }
                    if (members.size > 64) throw AssemblyProblem("В одном динамическом выходе может быть не более 64 профилей")
                    val supportedMembers = members.filterNot(::unsupportedInterface)
                    val candidates = supportedMembers.filter { it !in unavailable }.map { member ->
                        profile(member, channelPath, target.id).tag!!
                    }
                    if (candidates.isEmpty()) {
                        if (supportedMembers.isEmpty() && members.isNotEmpty()) Resolution(null) else fallback(target.fallback)
                    } else {
                        val tag = "folder-" + digest(identity(target.id, channelPath))
                        val lifecycle = lifecycle(preferred, channelPath, target.id)
                        val preferredTag = preferred?.takeIf { it in supportedMembers && it !in unavailable }
                            ?.let { physicalTag(it, channelPath, target.id) }
                        folderExits[tag] = PolicyFolderExit(
                            tag, target.id, channelPath, candidates, preferredTag, folderPolicy, lifecycle,
                        )
                        if (candidates.any { it in unproxiedExitTags }) unproxiedExitTags += tag
                        if (candidates.any { it in tcpOnlyExitTags }) tcpOnlyExitTags += tag
                        outbounds[tag] = buildJsonObject {
                            put("type", "selector")
                            put("tag", tag)
                            put("outbounds", JsonArray(candidates.map(::JsonPrimitive)))
                            preferredTag?.takeIf { it in candidates }?.let { put("default", it) }
                            put("interrupt_exist_connections", false)
                        }
                        // The native folder gate chooses on first traffic; this is never a preselected single profile.
                        val dnsProfile = preferred?.takeIf { it in supportedMembers && it !in unavailable }
                            ?: supportedMembers.first { it !in unavailable }
                        Resolution(tag, profiles[dnsProfile], folder = true)
                    }
                }
            }
        }

        private fun fallback(fallback: UnavailableFallback) = Resolution(if (fallback == UnavailableFallback.DIRECT) DIRECT_TAG else null)

        private fun unsupportedInterface(id: String): Boolean {
            if (platform != EnginePlatform.ANDROID) return false
            val unsupported = profiles[id]?.let(ExternalExitProfiles::platformRequirement) == RoutePlatform.WINDOWS
            if (unsupported) notes += ExternalExitProfiles.ANDROID_INTERFACE_UNSUPPORTED
            return unsupported
        }

        private fun lifecycle(profileId: String?, channelPath: List<String>, folderId: String?): ExitLifecyclePolicy =
            channelPath.lastOrNull()?.let { channels[it]?.lifecycle }
                ?: folderId?.let { folderPolicies[it]?.lifecycle ?: ExitLifecyclePolicy() }
                ?: profileId?.let { profilePolicies[it]?.lifecycle }
                ?: ExitLifecyclePolicy()

        private fun profile(id: String, channelPath: List<String>, folderId: String? = null): Resolution {
            val profile = profiles[id] ?: throw AssemblyProblem("Профиль выхода удалён")
            val tag = physicalTag(id, channelPath, folderId)
            if (tag in physical) return Resolution(tag, profile)
            physical[tag] = PolicyPhysicalExit(tag, id, channelPath, lifecycle(id, channelPath, folderId), folderId)
            val sources = profile.outbounds.associateBy { it.tag }
            if (sources.size != profile.outbounds.size) throw AssemblyProblem("У профиля повторяются теги внутренних выходов")
            val selected = profile.outbounds.firstOrNull { it.id == profile.selectedOutboundId }
                ?: throw AssemblyProblem("У профиля не выбран выход")
            val tags = sources.mapValues { (_, source) -> if (source.id == selected.id) tag else "$tag-dep-${digest(source.id)}" }
            outboundTagsByExit[tag] = tags
            val walking = linkedSetOf<String>()
            val complete = mutableSetOf<String>()
            val parsed = mutableMapOf<String, JsonObject>()
            fun add(sourceTag: String) {
                if (sourceTag in complete) return
                if (walking.size >= 64) throw AssemblyProblem("Слишком большая глубина внутренних выходов профиля")
                if (!walking.add(sourceTag)) throw AssemblyProblem("У профиля цикл внутренних выходов")
                val source = sources[sourceTag] ?: throw AssemblyProblem("Внутренний выход профиля отсутствует")
                val raw = json.parseToJsonElement(source.singBoxJson) as? JsonObject
                    ?: throw AssemblyProblem("Некорректный формат выхода профиля")
                parsed[sourceTag] = raw
                val type = (raw["type"] as? JsonPrimitive)?.contentOrNull
                if (type.isNullOrBlank() || type in setOf("block", "dns", "wireguard", "urltest")) {
                    throw AssemblyProblem("Тип внутреннего выхода не поддерживается экспертным режимом этого ядра")
                }
                val dependencies = listOfNotNull((raw["detour"] as? JsonPrimitive)?.contentOrNull) +
                    (raw["outbounds"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                dependencies.forEach(::add)
                val rewritten = raw.toMutableMap().apply {
                    put("tag", JsonPrimitive(tags.getValue(sourceTag)))
                    if (raw.containsKey("detour")) put("detour", JsonPrimitive(tags.getValue(raw.getValue("detour").jsonPrimitive.content)))
                    if (raw.containsKey("outbounds")) {
                        put(
                            "outbounds",
                            JsonArray(
                                (raw.getValue("outbounds") as JsonArray).map {
                                    JsonPrimitive(tags.getValue(it.jsonPrimitive.content))
                                }
                            )
                        )
                        (raw["default"] as? JsonPrimitive)?.contentOrNull?.let { put("default", JsonPrimitive(tags.getValue(it))) }
                    }
                    // Bootstrap resolves proxy server names over the physical network, never recursively through this exit.
                    if (type != "selector") {
                        put("domain_resolver", JsonPrimitive(BOOTSTRAP_DNS_TAG))
                        if ("connect_timeout" !in raw) put("connect_timeout", JsonPrimitive("30s"))
                    }
                }
                val normalized = XhttpMode.normalize(JsonObject(rewritten), defaults.xmuxConcurrency)
                normalized.note?.let(notes::add)
                outbounds[tags.getValue(sourceTag)] = normalized.outbound
                walking.remove(sourceTag)
                complete += sourceTag
            }
            add(selected.tag)
            fun canDialDirect(sourceTag: String): Boolean {
                val raw = parsed.getValue(sourceTag)
                return when (raw["type"]?.jsonPrimitive?.content) {
                    "direct" -> if (ExternalExitProfiles.binding(raw) != null) {
                        false
                    } else {
                        (raw["detour"] as? JsonPrimitive)?.contentOrNull?.let(::canDialDirect) ?: true
                    }
                    "selector" -> (raw["outbounds"] as? JsonArray).orEmpty().any { canDialDirect(it.jsonPrimitive.content) }
                    else -> false // A direct underlay detour does not remove the proxy protocol above it.
                }
            }
            if (canDialDirect(selected.tag)) unproxiedExitTags += tag
            fun isTcpOnly(sourceTag: String): Boolean {
                val raw = parsed.getValue(sourceTag)
                if ((raw["network"] as? JsonPrimitive)?.contentOrNull == "tcp") return true
                return when (raw["type"]?.jsonPrimitive?.content) {
                    "http" -> true
                    "socks" -> (raw["version"] as? JsonPrimitive)?.contentOrNull in setOf("4", "4a")
                    "selector" -> (raw["outbounds"] as? JsonArray).orEmpty().any { isTcpOnly(it.jsonPrimitive.content) }
                    "direct" -> (raw["detour"] as? JsonPrimitive)?.contentOrNull?.let(::isTcpOnly) ?: false
                    else -> false
                }
            }
            if (isTcpOnly(selected.tag)) tcpOnlyExitTags += tag
            return Resolution(tag, profile)
        }

        private fun routeRule(rule: ProgramRule, resolution: Resolution): JsonObject = buildJsonObject {
            if (rule.protected && resolution.tag?.let { it in unproxiedExitTags } == true) {
                throw AssemblyProblem("Защищённый выход содержит прямой маршрут. Выберите профиль без прямого выхода.")
            }
            rule.condition.forEach { (key, value) -> put(key, value) }
            put("lernet_node_ids", JsonArray(rule.nodeIds.map(::JsonPrimitive)))
            if (resolution.tag == null || rule.protected && resolution.tag == DIRECT_TAG) {
                put("action", "reject")
            } else {
                put("action", "route")
                put("outbound", resolution.tag)
                put("lernet_protected", rule.protected)
                put(
                    "lernet_fallback",
                    if (fallbackOf(rule.target.target) == UnavailableFallback.DIRECT && !rule.protected) "direct" else "block"
                )
                rule.redirect?.address?.let { address ->
                    put(
                        "override_address",
                        PolicyDestinationAddress.normalize(address) ?: throw AssemblyProblem("Некорректный адрес перенаправления")
                    )
                }
                rule.redirect?.port?.let { put("override_port", it) }
            }
        }

        private fun dnsRulesFor(rule: ProgramRule, resolution: Resolution): List<JsonObject> {
            val projection = PolicyDnsProjection.project(rule.condition)
            if (projection.inexact && !rule.protected && rule.target.target != PolicyTarget.Block) return emptyList()
            if (projection.inexact) {
                if (containsField(rule.condition, setOf("ip_cidr", "ip_is_private", "rule_set"))) {
                    notes += if (resolution.tag == null) {
                        "До ответа DNS IP и страна неизвестны. Запрет применяется к DNS шире " +
                            "и может мешать разрешению имён других программ."
                    } else {
                        "Защита по IP/стране: DNS отправляется через защищённый выход до получения адреса. " +
                            "Это может затронуть больше запросов."
                    }
                }
                if (ownerPredicate(rule.condition)) {
                    notes += if (resolution.tag == null) {
                        "DNS приложения может отправлять системная служба, и точный владелец неизвестен. " +
                            "Запрет блокирует такие DNS шире; другие программы тоже могут потерять разрешение имён."
                    } else {
                        "DNS приложения может отправлять системная служба. Защищённый выход применяется к DNS шире, " +
                            "чтобы запрос имени не ушёл напрямую из-за подмены владельца."
                    }
                }
            }
            if (resolution.tag == null || rule.protected && resolution.tag == DIRECT_TAG) {
                return listOf(JsonObject(projection.condition + mapOf("action" to JsonPrimitive("reject"))))
            }
            val binding = dnsBinding(resolution, rule.protected)
            return binding.rules.map { inner ->
                val actionKeys = DNS_ACTION_FIELDS
                val condition = JsonObject(inner.filterKeys { it !in actionKeys })
                val combined = ConditionJson.andAll(listOf(projection.condition, condition).filter { it.isNotEmpty() })
                JsonObject(
                    combined + inner.filterKeys { it in actionKeys } + buildMap {
                        if (rule.protected && inner["action"]?.jsonPrimitive?.content in setOf("route", "evaluate", "route-options")) {
                            put("disable_cache", JsonPrimitive(true))
                        }
                    }
                )
            } + buildJsonObject {
                projection.condition.forEach { (key, value) -> put(key, value) }
                put("action", "route")
                put("server", binding.final)
                binding.strategy?.let { put("strategy", it) }
                if (rule.protected) put("disable_cache", true)
            }
        }

        private fun dnsBinding(resolution: Resolution, protected: Boolean): DnsBinding {
            val tag = resolution.tag ?: return DnsBinding(DIRECT_DNS_TAG)
            if (tag == DIRECT_TAG) return DnsBinding(DIRECT_DNS_TAG)
            val profile = resolution.profile ?: profiles.getValue(physical.getValue(tag).profileId)
            val profileDns = DnsPolicy.fromStorage(profile.dnsPolicy)
            if (resolution.folder) {
                val folder = folderExits.getValue(tag)
                val settings = folder.candidateTags.map { candidate ->
                    val member = profiles.getValue(physical.getValue(candidate).profileId)
                    DnsPolicy.fromStorage(member.dnsPolicy) to preparedDns(member).toString()
                }
                if (settings.distinct().size > 1) {
                    throw AssemblyProblem(
                        "DNS: у выходов папки разные настройки DNS. " +
                            "Задайте одинаковые настройки для динамического выбора."
                    )
                }
            }
            if (!protected && profileDns == DnsPolicy.UNDERLAY) return DnsBinding(DIRECT_DNS_TAG)
            val prefix = "dns-$tag-${if (protected) "protected" else "profile"}"
            dnsBindings[prefix]?.let { return it }
            val prepared = preparedDns(profile)
            val sources = DnsDependency.dnsServers(prepared)
            val tcpOnly = tag in tcpOnlyExitTags
            val explicitServers = if (profileDns == DnsPolicy.PROFILE && !profile.dnsJson.isNullOrBlank()) {
                val original = json.parseToJsonElement(requireNotNull(profile.dnsJson)) as? JsonObject
                DnsDependency.dnsServers(original ?: JsonObject(emptyMap())).mapNotNull(DnsDependency::tagOf).toSet()
            } else {
                emptySet()
            }
            val names = sources.mapNotNull { source -> DnsDependency.tagOf(source)?.let { it to "$prefix-${digest(it)}" } }.toMap()
            val final = (prepared["final"] as? JsonPrimitive)?.contentOrNull
            if (final == null || final !in names || names.size != sources.size) {
                throw AssemblyProblem("DNS: некорректные ссылки серверов профиля")
            }
            if (!protected && platform == EnginePlatform.WINDOWS && profileDns == DnsPolicy.PROFILE && !profile.dnsJson.isNullOrBlank()) {
                val original = json.parseToJsonElement(requireNotNull(profile.dnsJson)) as? JsonObject
                if (original != null) {
                    val used = reachableDnsServers(original)
                    if (DnsDependency.dnsServers(original).any {
                            it["type"]?.jsonPrimitive?.contentOrNull == "dhcp" && DnsDependency.tagOf(it) in used
                        }
                    ) {
                        throw AssemblyProblem("DNS: для DHCP DNS профиля Windows укажите адрес сервера явно или выберите DNS исходной сети")
                    }
                }
            }
            validateDnsGroups(sources)
            val reachable = reachableDnsServers(prepared)
            sources.forEach { source ->
                val oldTag = DnsDependency.tagOf(source) ?: throw AssemblyProblem("DNS: сервер профиля без идентификатора")
                val type = (source["type"] as? JsonPrimitive)?.contentOrNull
                if (!protected && platform == EnginePlatform.WINDOWS && type in setOf("local", "dhcp")) {
                    if (type == "dhcp" && oldTag in explicitServers && oldTag in reachable) {
                        throw AssemblyProblem("DNS: для DHCP DNS профиля Windows укажите адрес сервера явно или выберите DNS исходной сети")
                    }
                    dnsServers[names.getValue(oldTag)] = buildJsonObject {
                        put("type", "local")
                        put("tag", names.getValue(oldTag))
                        put("lernet_preserve_destination", true)
                    }
                    if (oldTag in reachable) notes += "Имя DNS-сервера разрешается через DNS исходной сети, без публичной подмены."
                    return@forEach
                }
                if (protected && type in setOf("local", "dhcp", "fakeip")) {
                    dnsServers[names.getValue(oldTag)] = buildJsonObject {
                        put("type", if (tcpOnly) "tcp" else "udp")
                        put("tag", names.getValue(oldTag))
                        put("server", defaults.directDnsServer)
                        put("detour", tag)
                    }
                    notes += "Локальный DNS профиля заменён DNS через туннель для защищённых веток."
                    return@forEach
                }
                if (protected && type !in setOf("udp", "tcp", "tls", "https", "quic", "h3")) {
                    throw AssemblyProblem(
                        "DNS: этот тип сервера не подтверждает защищённый удалённый путь. " +
                            "Используйте UDP, TCP, TLS или HTTPS сервер для защищённой ветки."
                    )
                }
                val rewritten = source.toMutableMap().apply {
                    put("tag", JsonPrimitive(names.getValue(oldTag)))
                    if (type == "group") {
                        put(
                            "servers",
                            JsonArray(
                                dnsGroupMembers(source).map { member ->
                                    JsonPrimitive(names[member] ?: throw AssemblyProblem("DNS: сервер группы отсутствует"))
                                }
                            )
                        )
                    }
                    val ref = DnsDependency.resolverRef(source)
                    if (ref != null) put("domain_resolver", JsonPrimitive(names[ref] ?: BOOTSTRAP_DNS_TAG))
                    remove("address_resolver")
                    val detour = DnsDependency.detourOf(source)
                    val requiresExit = protected || detour != null
                    if (tcpOnly && type in setOf("udp", "quic", "h3") && requiresExit) {
                        if (oldTag in explicitServers || type != "udp") {
                            throw AssemblyProblem(
                                "DNS: этот выход передаёт только TCP. " +
                                    "Выберите TCP, TLS или HTTPS вместо UDP/QUIC DNS в профиле."
                            )
                        }
                        put("type", JsonPrimitive("tcp"))
                        notes += "Выход передаёт только TCP: DNS по умолчанию отправляется через него по TCP."
                    }
                    if (protected && type !in setOf("local", "dhcp", "fakeip")) {
                        put("detour", JsonPrimitive(tag))
                        put("domain_resolver", JsonPrimitive(BOOTSTRAP_DNS_TAG))
                    } else if (detour != null && resolution.folder) {
                        put("detour", JsonPrimitive(tag))
                    } else if (detour != null) {
                        val mapped = outboundTagsByExit[tag]?.get(detour) ?: throw AssemblyProblem("DNS: выход DNS профиля отсутствует")
                        put("detour", JsonPrimitive(mapped))
                    }
                }
                dnsServers[names.getValue(oldTag)] = JsonObject(rewritten)
            }
            val rules = (prepared["rules"] as? JsonArray).orEmpty().map { element ->
                val inner = element as? JsonObject ?: throw AssemblyProblem("DNS: некорректное правило профиля")
                if (inner.keys.any { it in setOf("outbound", "geoip", "geosite") }) {
                    throw AssemblyProblem("DNS: правило профиля содержит поля, удалённые из текущего ядра")
                }
                val action = inner["action"]?.jsonPrimitive?.contentOrNull ?: "route"
                if (action !in setOf("route", "reject", "predefined", "evaluate", "respond", "route-options")) {
                    throw AssemblyProblem("DNS: действие правила профиля не поддерживается")
                }
                JsonObject(
                    inner.toMutableMap().apply {
                        put("action", JsonPrimitive(action))
                        inner["server"]?.jsonPrimitive?.contentOrNull?.let { old ->
                            put("server", JsonPrimitive(names[old] ?: throw AssemblyProblem("DNS: сервер правила профиля отсутствует")))
                        }
                        if (action in setOf("route", "evaluate") && "server" !in this) {
                            put("server", JsonPrimitive(names.getValue(final)))
                        }
                        if (action == "evaluate") {
                            inner["tag"]?.jsonPrimitive?.contentOrNull?.let { put("tag", JsonPrimitive("$prefix-response-${digest(it)}")) }
                        }
                        fun remapResponse(element: JsonElement): JsonElement = when (element) {
                            is JsonObject -> JsonObject(
                                element.mapValues { (key, value) ->
                                    if (key == "match_response" && value is JsonPrimitive && value.isString) {
                                        JsonPrimitive("$prefix-response-${digest(value.content)}")
                                    } else {
                                        remapResponse(value)
                                    }
                                }
                            )
                            is JsonArray -> JsonArray(element.map(::remapResponse))
                            is JsonPrimitive -> element
                        }
                        keys.toList().forEach { key -> put(key, remapResponse(getValue(key))) }
                    }
                )
            }
            val originalDns = profile.dnsJson
            val strategy = if (profileDns == DnsPolicy.PROFILE && !originalDns.isNullOrBlank()) {
                (json.parseToJsonElement(originalDns) as? JsonObject)?.get("strategy")
            } else {
                null
            }
            // DnsBlock normalizes legacy profiles with ipv4_only. Do not inject that legacy action
            // into modern DNS rule configurations whose original profile has no strategy field.
            return DnsBinding(names.getValue(final), rules, strategy).also { dnsBindings[prefix] = it }
        }

        private fun preparedDns(profile: TransferProfile): JsonObject =
            DnsBlock.prepare(
                profile.dnsJson.takeIf { DnsPolicy.fromStorage(profile.dnsPolicy) == DnsPolicy.PROFILE },
                "proxy", DnsPolicy.PROFILE, defaults.directDnsServer,
            ).dns

        private fun dnsGroupMembers(source: JsonObject): List<String> {
            if (source["type"]?.jsonPrimitive?.contentOrNull != "group") return emptyList()
            val members = when (val value = source["servers"]) {
                is JsonArray -> value
                is JsonPrimitive -> JsonArray(listOf(value))
                else -> throw AssemblyProblem("DNS: у группы отсутствует список серверов")
            }
            if (members.isEmpty() || members.size > 64 || members.any { it !is JsonPrimitive || !it.isString || it.content.isBlank() }) {
                throw AssemblyProblem("DNS: группа должна содержать от 1 до 64 идентификаторов серверов")
            }
            return members.map { it.jsonPrimitive.content }
        }

        private fun validateDnsGroups(sources: List<JsonObject>) {
            val byTag = sources.associateBy { requireNotNull(DnsDependency.tagOf(it)) }
            val walking = mutableSetOf<String>()
            val complete = mutableSetOf<String>()
            fun visit(tag: String, depth: Int) {
                if (tag in complete) return
                if (depth > 64) throw AssemblyProblem("DNS: слишком большая глубина групп и зависимостей")
                if (!walking.add(tag)) throw AssemblyProblem("DNS: цикл групп и зависимостей серверов")
                val source = byTag[tag] ?: throw AssemblyProblem("DNS: сервер группы отсутствует")
                val dependencies = dnsGroupMembers(source) + listOfNotNull(DnsDependency.resolverRef(source))
                dependencies.forEach { dependency -> visit(dependency, depth + 1) }
                walking -= tag
                complete += tag
            }
            byTag.keys.forEach { visit(it, 0) }
        }

        /** Only referenced resolvers matter; numeric remote servers never invoke a hostname resolver. */
        private fun reachableDnsServers(dns: JsonObject): Set<String> {
            val sources = DnsDependency.dnsServers(dns).associateBy { DnsDependency.tagOf(it) }
            val pending = ArrayDeque<String>()
            dns["final"]?.jsonPrimitive?.contentOrNull?.let(pending::addLast)
            fun rules(element: JsonElement) {
                when (element) {
                    is JsonObject -> element.forEach { (key, value) ->
                        if (key == "server" && value is JsonPrimitive) {
                            pending += value.content
                        } else if (key == "rules") {
                            rules(value)
                        }
                    }
                    is JsonArray -> element.forEach(::rules)
                    is JsonPrimitive -> Unit
                }
            }
            dns["rules"]?.let(::rules)
            val used = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val tag = pending.removeFirst()
                if (!used.add(tag)) continue
                val source = sources[tag] ?: continue
                val server = source["server"]?.jsonPrimitive?.contentOrNull
                val numeric = server?.let(PolicyDestinationAddress::normalize)?.let {
                    ':' in it || Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+").matches(it)
                } == true
                if (server != null && !numeric) {
                    DnsDependency.resolverRef(source)?.let(pending::addLast)
                }
                if (source["type"]?.jsonPrimitive?.contentOrNull == "group") {
                    dnsGroupMembers(source).forEach(pending::addLast)
                }
            }
            return used
        }

        private fun collectRuleSets(conditions: List<JsonObject>): List<JsonObject> {
            val tags = linkedSetOf<String>()
            fun visit(element: JsonElement) {
                when (element) {
                    is JsonObject -> element.forEach { (key, value) ->
                        if (key == "rule_set") {
                            when (value) {
                                is JsonArray -> value.forEach { tags += it.jsonPrimitive.content }
                                is JsonPrimitive -> tags += value.content
                                else -> throw AssemblyProblem("Некорректный набор стран")
                            }
                        } else {
                            visit(value)
                        }
                    }
                    is JsonArray -> element.forEach(::visit)
                    is JsonPrimitive -> Unit
                }
            }
            conditions.forEach(::visit)
            if (tags.isNotEmpty() && directory.isBlank()) throw AssemblyProblem("Не указан каталог наборов стран")
            return tags.map { tag ->
                if (!tag.startsWith("geoip-") || !GeoRuleSets.isBundled(tag.removePrefix("geoip-"))) {
                    throw AssemblyProblem("Набор стран не поддерживается")
                }
                val file = File(directory, "$tag.srs")
                if (!file.isFile) throw AssemblyProblem("Файл набора стран отсутствует: $tag")
                buildJsonObject {
                    put("type", "local")
                    put("tag", tag)
                    put("format", "binary")
                    put("path", file.path.replace('\\', '/'))
                }
            }
        }
    }

    private data class Resolution(val tag: String?, val profile: TransferProfile? = null, val folder: Boolean = false)
    private data class DnsBinding(val final: String, val rules: List<JsonObject> = emptyList(), val strategy: JsonElement? = null)
    private class AssemblyProblem(message: String) : IllegalArgumentException(message)

    private fun fallbackOf(target: PolicyTarget): UnavailableFallback = when (target) {
        is PolicyTarget.Profile -> target.fallback
        is PolicyTarget.Folder -> target.fallback
        PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel -> UnavailableFallback.BLOCK
    }

    private fun ownerPredicate(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.keys.any { it in setOf("package_name", "process_name", "process_path") } ||
            element.values.any(::ownerPredicate)
        is JsonArray -> element.any(::ownerPredicate)
        is JsonPrimitive -> false
    }

    private fun unknownPackageCondition(): JsonObject = buildJsonObject {
        put("package_name_regex", JsonArray(listOf(JsonPrimitive(".+"))))
        put("invert", true)
    }

    private fun containsField(element: JsonElement, keys: Set<String>): Boolean = when (element) {
        is JsonObject -> element.keys.any { it in keys } || element.values.any { containsField(it, keys) }
        is JsonArray -> element.any { containsField(it, keys) }
        is JsonPrimitive -> false
    }

    private fun validProbeUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)
    }.getOrDefault(false)

    fun physicalTag(profileId: String, channelPath: List<String>, folderId: String? = null): String =
        "exit-" + digest(identity(profileId, channelPath) + folderId?.let { "folder:${it.length}:$it" }.orEmpty())

    private fun identity(id: String, channelPath: List<String>): String =
        (listOf(id) + canonicalChannelPath(channelPath)).joinToString("") { "${it.length}:$it" }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(24)

    private fun ingress(platform: EnginePlatform, defaults: EngineDefaults, underlay: String?): JsonObject = buildJsonObject {
        put(
            "inbounds",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        if (platform == EnginePlatform.WINDOWS) put("interface_name", ConfigAssembler.WINDOWS_TUN_INTERFACE)
                        put(
                            "address",
                            buildJsonArray {
                                add(JsonPrimitive(ConfigAssembler.TUN_ADDRESS))
                                add(JsonPrimitive(IPV6_TUN_ADDRESS))
                            }
                        )
                        put("mtu", defaults.tunMtu.coerceIn(1280, 9000))
                        put("stack", ConfigAssembler.TUN_STACK)
                        put("auto_route", true)
                        put("strict_route", platform == EnginePlatform.WINDOWS)
                        // Keep Windows DNS Client/NRPT/DoH selection. Queries still enter routing.
                        if (platform == EnginePlatform.WINDOWS) put("dns_mode", "disabled")
                    }
                )
            }
        )
        // Generations borrow this ingress network manager. Bind here as well as in policy options
        // so a newly installed default TUN route cannot become the physical egress interface.
        putJsonObject("route") {
            put("auto_detect_interface", platform == EnginePlatform.WINDOWS && underlay == null)
            underlay?.let { put("default_interface", it) }
        }
        putJsonObject("log") {
            put("level", "info")
            put("timestamp", true)
        }
    }

    private fun manifest(
        exits: List<PolicyPhysicalExit>,
        folders: List<PolicyFolderExit>,
        guardedOwner: Boolean,
        probeSchedule: ExpertProbeSchedule,
        probeUrl: String,
    ): JsonObject = buildJsonObject {
        put("direct_tag", DIRECT_TAG)
        put("probe_url", probeUrl)
        put("require_app_attribution", guardedOwner)
        putJsonObject("health") {
            put("probe_min_interval_ms", probeSchedule.minimumIntervalMs)
            put("probe_max_interval_ms", probeSchedule.maximumIntervalMs)
            put("active_probe_timeout_ms", probeSchedule.activeTimeoutMs)
            put("failed_checks_before_recovery", probeSchedule.failedChecksBeforeRecovery)
        }
        put(
            "exits",
            buildJsonArray {
                exits.forEach { exit ->
                    add(
                        buildJsonObject {
                            put("tag", exit.tag)
                            put("profile_id", exit.profileId)
                            exit.folderId?.let { put("folder_id", it) }
                            put("channel_path", JsonArray(exit.channelPath.map(::JsonPrimitive)))
                            put("mode", if (exit.lifecycle.coldStart) "cold" else "warm")
                            put("idle_timeout_ms", exit.lifecycle.idleTimeoutMs)
                            put("first_flow_timeout_ms", exit.lifecycle.firstFlowTimeoutMs)
                            put("startup_timeout_ms", exit.lifecycle.startupTimeoutMs)
                            put("max_pending_flows", exit.lifecycle.maxPendingFlows)
                        }
                    )
                }
            }
        )
        put(
            "folders",
            buildJsonArray {
                folders.forEach { folder ->
                    add(
                        buildJsonObject {
                            put("tag", folder.tag)
                            put("folder_id", folder.folderId)
                            put("channel_path", JsonArray(folder.channelPath.map(::JsonPrimitive)))
                            put("candidate_tags", JsonArray(folder.candidateTags.map(::JsonPrimitive)))
                            folder.preferredTag?.let { put("preferred_tag", it) }
                            put("auto_swap", folder.policy.autoSwap)
                            put("selection", if (folder.policy.selection == FolderSelection.LOWEST_LATENCY) "fastest" else "preferred")
                            put("probe_timeout_ms", probeSchedule.candidateTimeoutMs.coerceAtMost(folder.lifecycle.firstFlowTimeoutMs))
                            put("active_probe_timeout_ms", probeSchedule.activeTimeoutMs)
                            put("probe_min_interval_ms", probeSchedule.minimumIntervalMs)
                            put("probe_max_interval_ms", probeSchedule.maximumIntervalMs)
                            put("failed_checks_before_recovery", probeSchedule.failedChecksBeforeRecovery)
                            put("cooldown_ms", folder.policy.cooldownMs)
                            put("health_ttl_ms", folder.policy.freshnessMs)
                            put("max_pending_flows", folder.lifecycle.maxPendingFlows)
                            put("first_flow_timeout_ms", folder.lifecycle.firstFlowTimeoutMs)
                        }
                    )
                }
            }
        )
    }
}

internal data class PolicyDnsCondition(val condition: JsonObject, val inexact: Boolean)

/** IP/country matches are unknown before an answer. Protected predicates conservatively overmatch. */
internal object PolicyDnsProjection {
    private val addressFields = setOf("ip_cidr", "ip_is_private", "rule_set")
    private val unknownFields = addressFields + setOf("package_name", "process_name", "process_path")

    fun project(condition: JsonObject): PolicyDnsCondition {
        if (condition["type"]?.jsonPrimitive?.contentOrNull == "logical") {
            val parts = (condition["rules"] as? JsonArray).orEmpty().map { project(it as JsonObject) }
            val inexact = parts.any { it.inexact }
            if (!inexact) return PolicyDnsCondition(condition, false)
            val mode = condition["mode"]?.jsonPrimitive?.contentOrNull ?: "and"
            if (condition["invert"]?.jsonPrimitive?.contentOrNull == "true" && inexact) {
                return PolicyDnsCondition(JsonObject(emptyMap()), true)
            }
            if (mode == "or" && parts.any { it.condition.isEmpty() }) return PolicyDnsCondition(JsonObject(emptyMap()), inexact)
            val known = parts.map { it.condition }.filter { it.isNotEmpty() }
            val projected = when (known.size) {
                0 -> JsonObject(emptyMap())
                1 -> known.single()
                else -> buildJsonObject {
                    put("type", "logical")
                    put("mode", mode)
                    put("rules", JsonArray(known))
                }
            }
            return PolicyDnsCondition(projected, inexact)
        }
        val inexact = condition.keys.any { it in unknownFields }
        if (inexact && condition["invert"]?.jsonPrimitive?.contentOrNull == "true") {
            return PolicyDnsCondition(JsonObject(emptyMap()), true)
        }
        val dropped = if (condition.keys.any { it in addressFields }) {
            unknownFields + setOf("domain", "domain_suffix", "domain_keyword", "domain_regex")
        } else {
            unknownFields
        }
        return PolicyDnsCondition(JsonObject(condition.filterKeys { it !in dropped }), inexact)
    }
}
