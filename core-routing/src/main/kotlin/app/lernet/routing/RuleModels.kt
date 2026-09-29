package app.lernet.routing

import kotlinx.serialization.json.JsonObject

enum class RouteAction {
    PROXY,
    DIRECT,
    BLOCK,
    ;

    companion object {
        fun fromStorage(raw: String): RouteAction =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: error("unknown RouteAction: $raw")
    }
}

data class RuleMatch(
    val apps: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val domainSuffixes: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
    val geoip: List<String> = emptyList(),
    /** Windows executable names; Android's package names remain in [apps]. */
    val processes: List<String> = emptyList(),
) {
    fun isCatchAll(): Boolean =
        apps.isEmpty() &&
            domains.isEmpty() &&
            domainSuffixes.isEmpty() &&
            ipCidrs.isEmpty() &&
            geoip.isEmpty() &&
            processes.isEmpty()
}

data class RuleNode(
    val id: String,
    val parentId: String?,
    val enabled: Boolean,
    val sortIndex: Int,
    val match: RuleMatch,
    val action: RouteAction,
    val pipeName: String = "",
    /** Null keeps the legacy flat match. Set when the editor saved condition blocks. */
    val conditions: RuleConditions? = null,
)

enum class MatchKind {
    EXACT_DOMAIN,
    DOMAIN_SUFFIX,
    IP_CIDR,
    GEOIP,
    APP,
    CATCH_ALL,
}

data class Specificity(
    val kind: MatchKind,
    val cidrPrefix: Int,
    val treeOrder: Int,
) : Comparable<Specificity> {
    override fun compareTo(other: Specificity): Int {
        val kindCmp = kind.ordinal.compareTo(other.kind.ordinal)
        if (kindCmp != 0) return kindCmp
        if (kind == MatchKind.IP_CIDR) {
            val prefixCmp = other.cidrPrefix.compareTo(cidrPrefix)
            if (prefixCmp != 0) return prefixCmp
        }
        return treeOrder.compareTo(other.treeOrder)
    }
}

data class FieldError(
    val nodeId: String?,
    val field: String,
    val message: String,
)

data class CompiledRoute(
    val rules: List<CompiledRule>,
    val finalAction: RouteAction,
    val errors: List<FieldError>,
    val inactiveNodeIds: Set<String> = emptySet(),
) {
    val isValid: Boolean get() = errors.isEmpty()
}

data class CompiledRule(
    val nodeId: String,
    val specificity: Specificity,
    val match: RuleMatch,
    val action: RouteAction,
    val pipeName: String = "",
    val conditions: RuleConditions? = null,
    /** Set when a root-to-leaf path folded several condition objects. */
    val body: JsonObject? = null,
)
