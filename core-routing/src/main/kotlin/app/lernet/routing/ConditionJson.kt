package app.lernet.routing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns saved blocks into one sing-box rule object. Called from compile, not per packet.
 * Country codes become `rule_set` tags (`geoip-ru`). `invert` stays on its own sub-rule
 * so it does not flip sibling conditions.
 */
object ConditionJson {
    fun andAll(parts: List<JsonObject>): JsonObject = when {
        parts.isEmpty() -> buildJsonObject { }
        parts.size == 1 -> parts[0]
        else -> andParts(parts)
    }

    fun of(conditions: RuleConditions): JsonObject {
        val parts = conditions.blocks.mapNotNull { blockJson(it) }
        return when {
            parts.isEmpty() -> buildJsonObject { }
            parts.size == 1 -> parts[0]
            conditions.join == MatchJoin.OR -> logical("or", parts)
            else -> andParts(parts)
        }
    }

    private fun blockJson(block: ConditionBlock): JsonObject? {
        val values = block.values.map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) return null
        val body = when (block.kind) {
            ConditionKind.DOMAIN -> domainJson(values)
            ConditionKind.GEOIP -> geoJson(values)
            ConditionKind.PRIVATE -> geoJson(values)
            ConditionKind.CIDR -> signedField(values, "ip_cidr")
            ConditionKind.APP -> signedField(values, "package_name")
            ConditionKind.PROCESS -> signedField(values, "process_name")
        }
        return body.takeIf { it.isNotEmpty() }
    }

    private fun domainJson(patterns: List<String>): JsonObject {
        val positive = patterns.filterNot(PatternSign::negated)
        val negative = patterns.filter(PatternSign::negated).map(PatternSign::body)
        val parts = buildList {
            positiveRule(positive)?.let(::add)
            negative.forEach { token -> negativeDomain(token)?.let(::add) }
        }
        return joinOr(parts)
    }

    private fun positiveRule(patterns: List<String>): JsonObject? {
        if (patterns.isEmpty()) return null
        val (exact, suffixes) = DomainPatterns.split(patterns)
        val parts = buildList {
            if (exact.isNotEmpty()) add(buildJsonObject { put("domain", stringArray(exact)) })
            if (suffixes.isNotEmpty()) add(buildJsonObject { put("domain_suffix", stringArray(suffixes)) })
        }
        return joinOr(parts).takeIf { it.isNotEmpty() }
    }

    private fun negativeDomain(pattern: String): JsonObject? {
        val (exact, suffixes) = DomainPatterns.split(listOf(pattern))
        val body = when {
            exact.isNotEmpty() -> buildJsonObject { put("domain", stringArray(exact)) }
            suffixes.isNotEmpty() -> buildJsonObject { put("domain_suffix", stringArray(suffixes)) }
            else -> return null
        }
        return withInvert(body)
    }

    private fun geoJson(values: List<String>): JsonObject {
        val countries = mutableListOf<String>()
        val parts = mutableListOf<JsonObject>()
        var privatePlain = false
        var privateNegated = false
        values.forEach { token ->
            val body = PatternSign.body(token)
            val negated = PatternSign.negated(token)
            when {
                body.equals("private", ignoreCase = true) && negated -> privateNegated = true
                body.equals("private", ignoreCase = true) -> privatePlain = true
                negated -> parts += countryRule(body, negated = true)
                body.isNotEmpty() -> countries += body
            }
        }
        if (countries.isNotEmpty()) parts += countryGroup(countries)
        if (privatePlain) parts += buildJsonObject { put("ip_is_private", true) }
        if (privateNegated) parts += withInvert(buildJsonObject { put("ip_is_private", true) })
        return joinOr(parts)
    }

    private fun countryGroup(codes: List<String>): JsonObject = buildJsonObject {
        put("rule_set", stringArray(codes.map(GeoRuleSets::tag)))
    }

    private fun countryRule(code: String, negated: Boolean): JsonObject {
        val body = buildJsonObject { put("rule_set", JsonPrimitive(GeoRuleSets.tag(code))) }
        return if (negated) withInvert(body) else body
    }

    private fun signedField(values: List<String>, key: String): JsonObject {
        val positive = values.filterNot(PatternSign::negated).map(PatternSign::body).filter { it.isNotEmpty() }
        val negative = values.filter(PatternSign::negated).map(PatternSign::body).filter { it.isNotEmpty() }
        val parts = buildList {
            if (positive.isNotEmpty()) add(buildJsonObject { put(key, stringArray(positive)) })
            negative.forEach { body ->
                add(withInvert(buildJsonObject { put(key, stringArray(listOf(body))) }))
            }
        }
        return joinOr(parts)
    }

    private fun withInvert(body: JsonObject): JsonObject =
        JsonObject(body.toMap() + buildJsonObject { put("invert", true) }.toMap())

    private fun joinOr(parts: List<JsonObject>): JsonObject = when (parts.size) {
        0 -> buildJsonObject { }
        1 -> parts[0]
        else -> logical("or", parts)
    }

    private fun andParts(parts: List<JsonObject>): JsonObject {
        val keys = parts.flatMap { it.keys }
        val collision = keys.size != keys.toSet().size
        val nested = parts.any { it["type"] != null || it["invert"] != null }
        // sing-box ORs destination fields inside one default rule. Distinct blocks must
        // retain AND, e.g. a domain AND an IP range, not silently become domain OR range.
        val destinationFields = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "ip_cidr", "ip_is_private")
        val addressGroupCollision = parts.count { part -> part.keys.any { it in destinationFields } } > 1
        val ruleSetAddressCollision = parts.any { "rule_set" in it } && parts.any { part -> part.keys.any { it in destinationFields } }
        if (collision || nested || addressGroupCollision || ruleSetAddressCollision) return logical("and", parts)
        return buildJsonObject {
            parts.forEach { part -> part.forEach { (key, value) -> put(key, value) } }
        }
    }

    private fun logical(mode: String, rules: List<JsonObject>): JsonObject = buildJsonObject {
        put("type", "logical")
        put("mode", mode)
        put("rules", JsonArray(rules))
    }

    private fun stringArray(values: List<String>): JsonArray =
        JsonArray(values.map { JsonPrimitive(it) })
}
