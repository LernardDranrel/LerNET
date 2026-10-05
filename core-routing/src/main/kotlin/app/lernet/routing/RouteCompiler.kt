package app.lernet.routing

import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object RouteCompiler {
    private val ipv4Cidr = Regex("""^(\d{1,3}\.){3}\d{1,3}/(\d{1,2})$""")
    private val ipv6Cidr = Regex("""^[0-9a-fA-F:]+/\d{1,3}$""")
    private val packageName = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$""")
    private val invalidProcessCharacters = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')
    private val geoCode = Regex("""^!?([a-zA-Z]{2}|private)$""")

    /**
     * Depth-first by [RuleNode.sortIndex], then id. Canvas coordinates are not an input.
     * A node with children is a structural fork. Only leaf actions are emitted; ancestor constraints are AND-ed.
     * Sibling priority is [RuleNode.sortIndex]. Specificity does not reorder rules.
     */
    fun compile(input: List<RuleNode>, platform: RoutePlatform? = null): CompiledRoute {
        // Validate the same conditions that will be emitted, not stale legacy mirror fields.
        val normalized = input.map { node ->
            node.conditions?.let { node.copy(match = ConditionCodec.project(it)) } ?: node
        }
        val available = withoutDisabledDescendants(RouteTree.keepAttached(normalized))
        val inactive = platform?.let { RoutePlatformRules.inactiveNodeIds(available, it) }.orEmpty()
        val result = compileAvailable(available.filterNot { it.id in inactive }, inactive.isNotEmpty())
        return result.copy(inactiveNodeIds = inactive)
    }

    /** Retain disabled siblings for fallback validation, but their descendants cannot execute. */
    private fun withoutDisabledDescendants(nodes: List<RuleNode>): List<RuleNode> {
        val children = nodes.groupBy { it.parentId }
        val descendants = HashSet<String>()
        val pending = ArrayDeque<String>()
        nodes.filterNot { it.enabled }.forEach { node ->
            children[node.id].orEmpty().forEach { pending.add(it.id) }
        }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (descendants.add(id)) children[id].orEmpty().forEach { pending.add(it.id) }
        }
        return nodes.filterNot { it.id in descendants }
    }

    private fun compileAvailable(input: List<RuleNode>, platformFiltered: Boolean): CompiledRoute {
        if (platformFiltered && input.isEmpty()) {
            return CompiledRoute(emptyList(), RouteAction.PROXY, emptyList())
        }
        val nested = RouteTerminal.childErrors(input)
        val nodes = if (nested.isEmpty()) RouteTree.keepAttached(input) else emptyList()
        val errors = mutableListOf<FieldError>()
        errors += nested
        if (nodes.isEmpty()) {
            if (nested.isEmpty()) {
                errors += FieldError(null, "tree", "Дерево маршрутов пусто")
            }
            return CompiledRoute(emptyList(), RouteAction.PROXY, errors)
        }
        val enabled = nodes.filter { it.enabled }
        if (enabled.isEmpty()) {
            errors += FieldError(null, "tree", "Нет включённых правил")
            return CompiledRoute(emptyList(), RouteAction.PROXY, errors)
        }
        enabled.forEach { node -> errors += validate(node) }
        errors += RouteElse.errors(nodes)
        errors += RouteElse.blankRules(enabled)
        if (errors.isNotEmpty()) {
            return CompiledRoute(emptyList(), RouteAction.PROXY, errors)
        }
        val ordered = orderedLeaves(enabled)
        val tail = ordered.lastOrNull()?.takeIf { isDefaultTail(it) }
        val finalAction = tail?.action ?: RouteAction.PROXY
        val emitted = if (tail == null) ordered else ordered.dropLast(1)
        return CompiledRoute(emitted, finalAction, emptyList())
    }

    /** Depth-first, siblings by sortIndex. A node with children is never itself a leaf. */
    private fun orderedLeaves(nodes: List<RuleNode>): List<CompiledRule> {
        val byParent = nodes.groupBy { it.parentId }.mapValues { (_, kids) ->
            kids.sortedWith(compareBy<RuleNode> { it.sortIndex }.thenBy { it.id })
        }
        val out = mutableListOf<CompiledRule>()
        fun walk(node: RuleNode, ancestors: List<RuleNode>) {
            val path = ancestors + node
            val kids = byParent[node.id].orEmpty()
            if (kids.isEmpty()) {
                out += pathRule(path, out.size)
            } else {
                kids.forEach { child -> walk(child, path) }
            }
        }
        byParent[null].orEmpty().forEach { walk(it, emptyList()) }
        return out
    }

    private fun pathRule(path: List<RuleNode>, index: Int): CompiledRule {
        val leaf = path.last()
        val match = mergedMatch(path)
        val folded = path.size > 1
        return CompiledRule(
            nodeId = leaf.id,
            specificity = specificityOf(match, index),
            match = match,
            action = leaf.action,
            pipeName = leaf.pipeName,
            conditions = if (folded) null else leaf.conditions,
            body = if (folded) pathBody(path) else null,
        )
    }

    private fun pathBody(path: List<RuleNode>): JsonObject? {
        val parts = path.mapNotNull { constraintOf(it) }
        if (parts.isEmpty()) return null
        return ConditionJson.andAll(parts)
    }

    private fun constraintOf(node: RuleNode): JsonObject? {
        val json = node.conditions?.let(ConditionJson::of) ?: legacyMatch(node.match)
        return json.takeIf { it.isNotEmpty() }
    }

    private fun mergedMatch(path: List<RuleNode>): RuleMatch {
        val parts = path.map { node -> node.conditions?.let(ConditionCodec::project) ?: node.match }
        return RuleMatch(
            apps = parts.flatMap { it.apps },
            domains = parts.flatMap { it.domains },
            domainSuffixes = parts.flatMap { it.domainSuffixes },
            ipCidrs = parts.flatMap { it.ipCidrs },
            geoip = parts.flatMap { it.geoip },
            processes = parts.flatMap { it.processes },
        )
    }

    private fun isDefaultTail(rule: CompiledRule): Boolean {
        val emptyBody = rule.body == null || rule.body.isEmpty()
        val namedElse = rule.action == RouteAction.PROXY && rule.pipeName.isNotBlank()
        return rule.match.isCatchAll() && emptyBody && !namedElse
    }

    fun toSingBoxRules(
        compiled: CompiledRoute,
        proxyTag: String,
        pipeTags: Map<String, String> = emptyMap(),
    ): List<JsonObject> = compiled.rules.map { rule ->
        val body = rule.body ?: rule.conditions?.let(ConditionJson::of) ?: legacyMatch(rule.match)
        withAction(body, rule, proxyTag, pipeTags)
    }

    private fun legacyMatch(match: RuleMatch): JsonObject = ConditionJson.of(ConditionCodec.fromMatch(match))

    private fun withAction(
        body: JsonObject,
        rule: CompiledRule,
        proxyTag: String,
        pipeTags: Map<String, String>,
    ): JsonObject {
        val action = buildJsonObject {
            when (rule.action) {
                RouteAction.PROXY -> put("outbound", proxyOutbound(rule, proxyTag, pipeTags))
                RouteAction.DIRECT -> put("outbound", "direct")
                RouteAction.BLOCK -> put("action", "reject")
            }
        }
        return JsonObject(body.toMap() + action.toMap())
    }

    private fun proxyOutbound(rule: CompiledRule, proxyTag: String, pipeTags: Map<String, String>): String {
        val pipe = rule.pipeName.trim()
        if (pipe.isEmpty()) return proxyTag
        return pipeTags[pipe] ?: proxyTag
    }

    fun specificityOf(node: RuleNode, treeOrder: Int): Specificity =
        specificityOf(node.conditions?.let(ConditionCodec::project) ?: node.match, treeOrder)

    fun specificityOf(match: RuleMatch, treeOrder: Int): Specificity = when {
        match.domains.isNotEmpty() ->
            Specificity(MatchKind.EXACT_DOMAIN, 0, treeOrder)
        match.domainSuffixes.isNotEmpty() ->
            Specificity(MatchKind.DOMAIN_SUFFIX, 0, treeOrder)
        match.ipCidrs.isNotEmpty() ->
            Specificity(MatchKind.IP_CIDR, match.ipCidrs.maxOf { prefixLength(it) }, treeOrder)
        match.geoip.isNotEmpty() ->
            Specificity(MatchKind.GEOIP, 0, treeOrder)
        match.apps.isNotEmpty() ->
            Specificity(MatchKind.APP, 0, treeOrder)
        match.processes.isNotEmpty() ->
            Specificity(MatchKind.APP, 0, treeOrder)
        else ->
            Specificity(MatchKind.CATCH_ALL, 0, treeOrder)
    }

    fun prefixLength(cidr: String): Int = cidr.substringAfterLast("/", "0").toIntOrNull() ?: 0

    /** Field validation shared with the device policy; it does not require a legacy otherwise sibling. */
    fun validateConditions(nodeId: String, conditions: RuleConditions): List<FieldError> {
        val match = ConditionCodec.project(conditions)
        val node = RuleNode(nodeId, null, true, 0, match, RouteAction.PROXY, conditions = conditions)
        val emptyBlock = conditions.blocks.any { block -> block.values.none { PatternSign.body(it).isNotBlank() } }
        val required = if (emptyBlock) listOf(FieldError(nodeId, "match", "Заполните все блоки условий правила")) else emptyList()
        return (validate(node) + required).distinct()
    }

    fun assignTreeOrder(nodes: List<RuleNode>): Map<String, Int> {
        val byParent = nodes.groupBy { it.parentId }.mapValues { (_, children) ->
            children.sortedWith(compareBy<RuleNode> { it.sortIndex }.thenBy { it.id })
        }
        val order = linkedMapOf<String, Int>()
        var cursor = 0
        fun walk(parentId: String?) {
            byParent[parentId].orEmpty().forEach { node ->
                order[node.id] = cursor
                cursor += 1
                walk(node.id)
            }
        }
        walk(null)
        nodes.forEach { node ->
            if (node.id !in order) {
                order[node.id] = cursor
                cursor += 1
            }
        }
        return order
    }

    private fun validate(node: RuleNode): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (!node.match.isCatchAll() &&
            node.conditions?.blocks?.any { block ->
                block.values.none { PatternSign.body(it).isNotBlank() }
            } == true
        ) {
            errors += FieldError(node.id, "match", "Заполните все блоки условий правила")
        }
        node.match.domains.forEach { value ->
            val body = PatternSign.body(value)
            if (body.isBlank() || body.contains(' ')) {
                errors += FieldError(node.id, "domain", "Некорректный домен: '$value'")
            }
        }
        node.match.domainSuffixes.forEach { value ->
            val body = PatternSign.body(value)
            if (body.isBlank() || body.contains(' ')) {
                errors += FieldError(node.id, "domain_suffix", "Некорректный суффикс: '$value'")
            }
        }
        node.match.ipCidrs.forEach { value ->
            if (!isCidr(PatternSign.body(value))) {
                errors += FieldError(node.id, "ip_cidr", "Некорректный CIDR: '$value'")
            }
        }
        node.match.geoip.forEach { value ->
            errors += geoError(node.id, value)
        }
        node.match.apps.forEach { value ->
            if (!packageName.matches(PatternSign.body(value))) {
                errors += FieldError(node.id, "app", "Некорректный package name: '$value'")
            }
        }
        node.match.processes.forEach { value ->
            val body = PatternSign.body(value)
            if (body.isBlank() || body.any { it in invalidProcessCharacters || it.isISOControl() } || body == "." || body == "..") {
                errors += FieldError(node.id, "process_name", "Недопустимое имя процесса: '$value'")
            }
        }
        return errors
    }

    private fun isCidr(value: String): Boolean {
        if (ipv4Cidr.matches(value)) {
            val prefix = prefixLength(value)
            val octets = value.substringBefore("/").split(".").mapNotNull { it.toIntOrNull() }
            return prefix in 0..32 && octets.size == 4 && octets.all { it in 0..255 }
        }
        if (ipv6Cidr.matches(value)) {
            val prefix = prefixLength(value)
            val address = value.substringBefore('/')
            // Only numeric IPv6 text reaches the parser; never resolve a hostname here.
            return prefix in 0..128 &&
                ':' in address &&
                runCatching { InetAddress.getByName(address) is Inet6Address }.getOrDefault(false)
        }
        return false
    }

    private fun geoError(nodeId: String, value: String): List<FieldError> {
        if (!geoCode.matches(value)) {
            return listOf(FieldError(nodeId, "geoip", "Ожидался код страны ISO: '$value'"))
        }
        val code = PatternSign.body(value)
        if (code.equals("private", ignoreCase = true) || GeoRuleSets.isBundled(code)) {
            return emptyList()
        }
        return listOf(FieldError(nodeId, "geoip", "Нет набора адресов для страны: $code"))
    }
}

fun never(value: Any): Nothing = error("unhandled: $value")
