package app.lernet.routing

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One rule is an ordered list of blocks. Values inside a block are OR.
 * [MatchJoin] applies between blocks (default OR). Normalized once in [RouteCompiler].
 */
@Serializable
enum class MatchJoin {
    OR,
    AND,
}

@Serializable
enum class ConditionKind {
    DOMAIN,
    GEOIP,
    PRIVATE,
    CIDR,
    APP,
    PROCESS,
}

@Serializable
data class ConditionBlock(
    val kind: ConditionKind,
    val values: List<String> = emptyList(),
)

@Serializable
data class RuleConditions(
    val join: MatchJoin = MatchJoin.OR,
    val blocks: List<ConditionBlock> = emptyList(),
)

object DomainPatterns {
    fun join(domains: List<String>, suffixes: List<String>): List<String> {
        val globs = suffixes.map { suffix ->
            val body = PatternSign.body(suffix)
            PatternSign.signed(if ('*' in body) body else "*.$body", PatternSign.negated(suffix))
        }
        return domains + globs
    }

    fun split(patterns: List<String>): Pair<List<String>, List<String>> {
        val domains = mutableListOf<String>()
        val suffixes = mutableListOf<String>()
        patterns.map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            if ('*' in token) {
                val suffix = suffixOf(token)
                if (suffix.isNotEmpty()) suffixes += suffix
            } else {
                domains += token
            }
        }
        return domains to suffixes
    }

    fun suffixOf(token: String): String {
        val negative = PatternSign.negated(token)
        val body = PatternSign.body(token)
        val stripped = when {
            body.startsWith("*.") -> body.removePrefix("*.")
            body.startsWith("*") -> body.removePrefix("*").removePrefix(".")
            else -> body
        }
        return PatternSign.signed(stripped.trim('.'), negative)
    }
}

object ConditionCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(conditions: RuleConditions): String = json.encodeToString(conditions)

    fun decodeStrict(raw: String): RuleConditions = json.decodeFromString<RuleConditions>(raw)

    fun decode(raw: String?, match: RuleMatch): RuleConditions {
        if (raw.isNullOrBlank()) return fromMatch(match)
        // Nonempty structured data is authoritative. Invalid JSON stays an invalid rule,
        // never a fallback to different legacy conditions (or an unconditional action).
        return runCatching { decodeStrict(raw) }
            .getOrElse { RuleConditions() }
            .let { conditions ->
                conditions.copy(
                    blocks = conditions.blocks.map { block ->
                        if (block.kind == ConditionKind.GEOIP &&
                            block.values.isNotEmpty() &&
                            block.values.all { PatternSign.body(it).equals("private", ignoreCase = true) }
                        ) {
                            block.copy(kind = ConditionKind.PRIVATE)
                        } else {
                            block
                        }
                    }
                )
            }
    }

    /** Several kinds loaded from the old columns stay AND, matching one sing-box object. */
    fun fromMatch(match: RuleMatch): RuleConditions {
        val blocks = buildList {
            val patterns = DomainPatterns.join(match.domains, match.domainSuffixes)
            if (patterns.isNotEmpty()) add(ConditionBlock(ConditionKind.DOMAIN, patterns))
            val privateNetworks = match.geoip.filter { PatternSign.body(it).equals("private", ignoreCase = true) }
            val countries = match.geoip - privateNetworks.toSet()
            when {
                // A legacy mixed block meant OR within GeoIP. Splitting it would change routing.
                countries.isNotEmpty() -> add(ConditionBlock(ConditionKind.GEOIP, match.geoip))
                privateNetworks.isNotEmpty() -> add(ConditionBlock(ConditionKind.PRIVATE, privateNetworks))
            }
            if (match.ipCidrs.isNotEmpty()) add(ConditionBlock(ConditionKind.CIDR, match.ipCidrs))
            if (match.apps.isNotEmpty()) add(ConditionBlock(ConditionKind.APP, match.apps))
            if (match.processes.isNotEmpty()) add(ConditionBlock(ConditionKind.PROCESS, match.processes))
        }
        val join = if (blocks.map { it.kind }.distinct().size > 1) MatchJoin.AND else MatchJoin.OR
        return RuleConditions(join, blocks)
    }

    fun project(conditions: RuleConditions): RuleMatch {
        val domains = mutableListOf<String>()
        val suffixes = mutableListOf<String>()
        val geoip = mutableListOf<String>()
        val cidrs = mutableListOf<String>()
        val apps = mutableListOf<String>()
        val processes = mutableListOf<String>()
        conditions.blocks.forEach { block ->
            val values = block.values.map { it.trim() }.filter { it.isNotEmpty() }
            when (block.kind) {
                ConditionKind.DOMAIN -> {
                    val (exact, globs) = DomainPatterns.split(values)
                    domains += exact
                    suffixes += globs
                }
                ConditionKind.GEOIP -> geoip += values
                ConditionKind.PRIVATE -> geoip += values
                ConditionKind.CIDR -> cidrs += values
                ConditionKind.APP -> apps += values
                ConditionKind.PROCESS -> processes += values
            }
        }
        return RuleMatch(apps, domains, suffixes, cidrs, geoip, processes)
    }
}
