package app.lernet.routing.policy

import app.lernet.routing.GeoRuleSets
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Supplied metadata only. Null means unknown; no DNS, process discovery or GeoIP request occurs here. */
data class PolicySimulationInput(
    val domain: String? = null,
    val destinationIp: String? = null,
    val sourceIp: String? = null,
    val processName: String? = null,
    val packageName: String? = null,
    val network: String? = null,
    val protocol: String? = null,
    val destinationPort: Int? = null,
    val sourcePort: Int? = null,
    val geoCountry: String? = null,
    val ruleSetMatches: Map<String, Boolean> = emptyMap(),
)

enum class PolicyMatchResult { MATCH, NO_MATCH, UNKNOWN }
enum class PolicyPreviewConfidence { MATCHED, INCOMPLETE, NO_MATCH, INVALID }
data class PolicyConditionMatch(val result: PolicyMatchResult, val missingFacts: Set<String> = emptySet())
data class PolicyPreviewCandidate(val ruleIndex: Int, val rule: ProgramRule, val match: PolicyConditionMatch)
data class PolicyRoutePreview(
    val confidence: PolicyPreviewConfidence,
    val selected: PolicyPreviewCandidate? = null,
    val candidates: List<PolicyPreviewCandidate> = emptyList(),
    val missingFacts: Set<String> = emptySet(),
    val errors: List<String> = emptyList(),
) {
    val nodeIds: List<String> get() = selected?.rule?.nodeIds.orEmpty()
    val scopes: List<PolicyScope> get() = selected?.rule?.scopes.orEmpty()
    val target: PolicyExit? get() = selected?.rule?.target
    val protected: Boolean? get() = selected?.rule?.protected
    val redirect: DestinationRedirect? get() = selected?.rule?.redirect
}

/** Conservative preview of a compiled program, never a claim that a packet traversed a real tunnel. */
object PolicyFlowMatcher {
    fun preview(program: PolicyProgram, input: PolicySimulationInput): PolicyRoutePreview {
        if (!program.isValid) return PolicyRoutePreview(PolicyPreviewConfidence.INVALID, errors = program.errors.map { it.message })
        val candidates = mutableListOf<PolicyPreviewCandidate>()
        var ambiguous = false
        program.rules.forEachIndexed { index, rule ->
            val evaluated = match(rule.condition, input)
            if (evaluated.result != PolicyMatchResult.NO_MATCH) {
                val candidate = PolicyPreviewCandidate(index, rule, evaluated)
                if (candidates.size < MAX_CANDIDATES) candidates += candidate
                if (evaluated.result == PolicyMatchResult.UNKNOWN) ambiguous = true
                if (evaluated.result == PolicyMatchResult.MATCH) {
                    return PolicyRoutePreview(
                        if (ambiguous) PolicyPreviewConfidence.INCOMPLETE else PolicyPreviewConfidence.MATCHED,
                        if (ambiguous) null else candidate,
                        candidates,
                        candidates.flatMap { it.match.missingFacts }.toSet(),
                    )
                }
            }
        }
        return PolicyRoutePreview(
            if (ambiguous) PolicyPreviewConfidence.INCOMPLETE else PolicyPreviewConfidence.NO_MATCH,
            candidates = candidates,
            missingFacts = candidates.flatMap { it.match.missingFacts }.toSet(),
        )
    }

    fun match(condition: JsonObject, input: PolicySimulationInput): PolicyConditionMatch = evaluate(condition, input, 0)

    private fun evaluate(condition: JsonObject, input: PolicySimulationInput, depth: Int): PolicyConditionMatch {
        if (depth > MAX_DEPTH) return unknown("Глубина условия превышает предел предпросмотра")
        val result = if (condition["type"]?.primitiveContent() == "logical") {
            val rules = condition["rules"] as? JsonArray ?: return unknown("Некорректное логическое условие")
            val children = rules.map { (it as? JsonObject)?.let { rule -> evaluate(rule, input, depth + 1) } ?: unknown("Формат условия") }
            when (condition["mode"]?.primitiveContent()) {
                "and" -> and(children)
                "or" -> or(children)
                else -> unknown("Логический оператор")
            }
        } else if (condition["type"] != null && condition["type"]?.primitiveContent() != "default") {
            unknown("Тип условия")
        } else {
            val groups = mutableListOf<PolicyConditionMatch>()
            val groupedKeys = ADDRESS_FIELDS + DESTINATION_PORT_FIELDS + SOURCE_ADDRESS_FIELDS + SOURCE_PORT_FIELDS
            val opaqueAddressMerge = condition["rule_set"] != null && condition.keys.any { it in ADDRESS_FIELDS }
            listOf(ADDRESS_FIELDS, DESTINATION_PORT_FIELDS, SOURCE_ADDRESS_FIELDS, SOURCE_PORT_FIELDS).forEach { keys ->
                val fields = condition.filter { (key, value) ->
                    key in keys && !(value is JsonArray && value.isEmpty()) && !(opaqueAddressMerge && key in ADDRESS_FIELDS)
                }
                if (fields.isNotEmpty()) groups += or(fields.map { (key, value) -> field(key, value, input) })
            }
            condition.filter { (key, value) ->
                key !in groupedKeys &&
                    key !in META_FIELDS &&
                    !(value is JsonArray && value.isEmpty()) &&
                    !(opaqueAddressMerge && key == "rule_set")
            }.forEach { (key, value) ->
                groups += field(key, value, input)
            }
            // Opaque native rule-sets may merge address fields into the outer OR group. Do not invent that shape.
            if (opaqueAddressMerge) {
                groups += unknown("Содержимое rule-set и его объединение с адресным условием")
            }
            and(groups)
        }
        val invert = (condition["invert"] as? JsonPrimitive)?.booleanOrNull
        if (condition["invert"] != null && invert == null) return unknown("Некорректная инверсия")
        return if (invert == true) {
            result.copy(
                result = when (result.result) {
                    PolicyMatchResult.MATCH -> PolicyMatchResult.NO_MATCH
                    PolicyMatchResult.NO_MATCH -> PolicyMatchResult.MATCH
                    PolicyMatchResult.UNKNOWN -> PolicyMatchResult.UNKNOWN
                }
            )
        } else {
            result
        }
    }

    private fun field(key: String, value: JsonElement, input: PolicySimulationInput): PolicyConditionMatch {
        val values = strings(value) ?: return unknown("Формат поля $key")
        return when (key) {
            "domain" -> known(input.domain, "Домен") { domain -> values.any { it == normalizeDomain(domain) } }
            "domain_suffix" -> known(input.domain, "Домен") { domain ->
                val normalized = normalizeDomain(domain)
                values.any { suffix ->
                    val token = suffix
                    if (token.startsWith('.')) normalized.endsWith(token) else normalized == token || normalized.endsWith(".$token")
                }
            }
            "domain_keyword" -> known(input.domain, "Домен") { domain -> values.any { normalizeDomain(domain).contains(it) } }
            // Native RE2 and JVM regex differ and can have different complexity; preview never executes a saved arbitrary regex.
            "domain_regex", "process_path_regex", "package_name_regex" -> unknown("Регулярное выражение $key проверяет только ядро")
            "ip_cidr" -> addressMatch(input.destinationIp, values, "IP назначения")
            "source_ip_cidr" -> addressMatch(input.sourceIp, values, "IP источника")
            "ip_is_private" -> privateMatch(input.destinationIp, value, "IP назначения")
            "source_ip_is_private" -> privateMatch(input.sourceIp, value, "IP источника")
            "process_name" -> known(input.processName, "Имя процесса") { process -> values.any { it == process } }
            "package_name" -> known(input.packageName, "Пакет приложения Android") { app -> app in values }
            "network" -> known(input.network, "TCP/UDP/ICMP") { network -> network in values }
            "protocol" -> known(input.protocol, "Протокол после распознавания ядром") { protocol ->
                protocol in values
            }
            "port" -> portMatch(input.destinationPort, values, "Порт назначения", ranges = false)
            "port_range" -> portMatch(input.destinationPort, values, "Порт назначения", ranges = true)
            "source_port" -> portMatch(input.sourcePort, values, "Порт источника", ranges = false)
            "source_port_range" -> portMatch(input.sourcePort, values, "Порт источника", ranges = true)
            "rule_set" -> or(values.map { tag -> ruleSetMatch(tag, input) })
            else -> unknown("Поле $key не задано в модели предпросмотра")
        }
    }

    private fun ruleSetMatch(tag: String, input: PolicySimulationInput): PolicyConditionMatch {
        input.ruleSetMatches[tag]?.let { return booleanMatch(it) }
        val country = input.geoCountry ?: return unknown("Результат rule-set $tag или страна назначения")
        if (!validCountryCode(country)) {
            return unknown("Страна назначения: укажите двухбуквенный код ISO, например RU или DE; название страны не подходит")
        }
        val taggedCountry = tag.removePrefix("geoip-")
        if (!tag.startsWith("geoip-") || !validCountryCode(taggedCountry) || taggedCountry != taggedCountry.lowercase(Locale.ROOT)) {
            return unknown("Содержимое rule-set $tag")
        }
        return booleanMatch(tag == GeoRuleSets.tag(country))
    }

    private fun validCountryCode(value: String): Boolean = value.length == 2 &&
        value.all { it in 'a'..'z' || it in 'A'..'Z' } &&
        value.lowercase(Locale.ROOT) in COUNTRY_CODES

    private fun addressMatch(value: String?, cidrs: List<String>, label: String): PolicyConditionMatch {
        val address = value?.let(::literalAddress) ?: return unknown(label)
        return or(
            cidrs.map { cidr ->
                val parts = cidr.split('/')
                val network = literalAddress(parts.firstOrNull().orEmpty()) ?: return@map unknown("Некорректный CIDR")
                val prefix = if (parts.size == 1) network.size * 8 else parts.getOrNull(1)?.toIntOrNull()
                if (parts.size > 2 || prefix == null || prefix !in 0..network.size * 8) return@map unknown("Некорректный CIDR")
                if (address.size != network.size) return@map booleanMatch(false)
                val wholeBytes = prefix / 8
                val remaining = prefix % 8
                val sameWhole = (0 until wholeBytes).all { address[it] == network[it] }
                val sameTail = remaining == 0 ||
                    (
                        (address[wholeBytes].toInt() xor network[wholeBytes].toInt()) and
                            (0xff shl (8 - remaining))
                        ) == 0
                booleanMatch(sameWhole && sameTail)
            }
        )
    }

    private fun privateMatch(value: String?, expected: JsonElement, label: String): PolicyConditionMatch {
        val flag = (expected as? JsonPrimitive)?.booleanOrNull ?: return unknown("Формат ip_is_private")
        if (!flag) return unknown("ip_is_private=false не задаёт отрицание; используйте invert у отдельного условия")
        val parsed = value?.let(::literalAddress) ?: return unknown(label)
        val mapped = parsed.size == 16 &&
            parsed.take(10).all { it == 0.toByte() } &&
            parsed[10] == 0xff.toByte() &&
            parsed[11] == 0xff.toByte()
        val address = if (mapped) parsed.copyOfRange(12, 16) else parsed
        // Mirror sing/common/network.IsPublicAddr in the pinned core; it includes multicast and unspecified addresses.
        val private = if (address.size == 4) {
            val first = address[0].toInt() and 0xff
            val second = address[1].toInt() and 0xff
            first == 10 ||
                first == 127 ||
                first == 172 &&
                second in 16..31 ||
                first == 192 &&
                second == 168 ||
                first == 169 &&
                second == 254 ||
                first in 224..239 ||
                address.all { it == 0.toByte() }
        } else {
            val first = address[0].toInt() and 0xff
            val second = address[1].toInt() and 0xff
            val loopback = address.take(15).all { it == 0.toByte() } && address[15] == 1.toByte()
            (first and 0xfe) == 0xfc ||
                first == 0xfe &&
                (second and 0xc0) == 0x80 ||
                loopback ||
                first == 0xff ||
                address.all { it == 0.toByte() }
        }
        return booleanMatch(private == flag)
    }

    private fun portMatch(port: Int?, values: List<String>, label: String, ranges: Boolean): PolicyConditionMatch {
        if (port == null || port !in 0..65535) return unknown(label)
        return or(
            values.map { token ->
                if (!ranges) {
                    token.toIntOrNull()?.takeIf { it in 0..65535 }?.let { booleanMatch(port == it) } ?: unknown("Формат порта")
                } else {
                    val bounds = token.split(':')
                    val lowText = bounds.getOrNull(0).orEmpty()
                    val highText = bounds.getOrNull(1).orEmpty()
                    val low = if (lowText.isEmpty()) 0 else lowText.toIntOrNull() ?: return@map unknown("Формат диапазона портов")
                    val high = if (highText.isEmpty()) 65535 else highText.toIntOrNull() ?: return@map unknown("Формат диапазона портов")
                    if (bounds.size != 2 || low !in 0..65535 || high !in low..65535) {
                        unknown("Формат диапазона портов")
                    } else {
                        booleanMatch(port in low..high)
                    }
                }
            }
        )
    }

    private fun known(value: String?, label: String, predicate: (String) -> Boolean): PolicyConditionMatch =
        if (value == null) unknown(label) else booleanMatch(predicate(value))

    private fun and(matches: List<PolicyConditionMatch>): PolicyConditionMatch = combine(matches, all = true)
    private fun or(matches: List<PolicyConditionMatch>): PolicyConditionMatch = combine(matches, all = false)
    private fun combine(matches: List<PolicyConditionMatch>, all: Boolean): PolicyConditionMatch {
        val decisive = if (all) PolicyMatchResult.NO_MATCH else PolicyMatchResult.MATCH
        if (matches.any { it.result == decisive }) return PolicyConditionMatch(decisive)
        val missing = matches.filter { it.result == PolicyMatchResult.UNKNOWN }.flatMap { it.missingFacts }.toSet()
        if (missing.isNotEmpty()) return PolicyConditionMatch(PolicyMatchResult.UNKNOWN, missing)
        return booleanMatch(all)
    }

    private fun unknown(fact: String) = PolicyConditionMatch(PolicyMatchResult.UNKNOWN, setOf(fact))
    private fun booleanMatch(value: Boolean) = PolicyConditionMatch(if (value) PolicyMatchResult.MATCH else PolicyMatchResult.NO_MATCH)
    private fun normalizeDomain(value: String): String = value.lowercase()
    private fun strings(value: JsonElement): List<String>? {
        return when (value) {
            is JsonPrimitive -> value.contentOrNull?.let(::listOf)
            is JsonArray -> value.map { it.primitiveContent() ?: return null }
            is JsonObject -> null
        }
    }
    private fun JsonElement.primitiveContent(): String? = (this as? JsonPrimitive)?.contentOrNull

    /** Strict numeric-only parser: even malformed input cannot accidentally trigger DNS resolution. */
    private fun literalAddress(raw: String): ByteArray? {
        if (raw.contains('%') || raw.contains('[') || raw.contains(']')) return null
        if (!raw.contains(':')) return ipv4(raw)
        if (raw.any { it !in "0123456789abcdefABCDEF:." } || raw.indexOf("::") != raw.lastIndexOf("::")) return null
        var value = raw
        if (value.contains('.')) {
            val index = value.lastIndexOf(':')
            val tail = ipv4(value.substring(index + 1)) ?: return null
            val high = ((tail[0].toInt() and 0xff) shl 8) or (tail[1].toInt() and 0xff)
            val low = ((tail[2].toInt() and 0xff) shl 8) or (tail[3].toInt() and 0xff)
            value = value.substring(0, index + 1) + high.toString(16) + ":" + low.toString(16)
        }
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { token ->
                if (token.isEmpty() || token.length > 4) return null
                token.toIntOrNull(16)?.takeIf { it in 0..65535 } ?: return null
            }
        }
        val compressed = value.contains("::")
        val sides = value.split("::")
        val left = groups(sides.first()) ?: return null
        val right = if (compressed) groups(sides.last()) ?: return null else emptyList()
        val values = if (compressed) {
            if (left.size + right.size >= 8) return null
            left + List(8 - left.size - right.size) { 0 } + right
        } else {
            if (left.size != 8) return null
            left
        }
        return values.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
    }

    private fun ipv4(value: String): ByteArray? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        return parts.map { token ->
            if (token.isEmpty() ||
                token.any { it !in '0'..'9' } ||
                token.length > 3 ||
                token.length > 1 &&
                token.startsWith('0')
            ) {
                return null
            }
            token.toIntOrNull()?.takeIf { it in 0..255 }?.toByte() ?: return null
        }.toByteArray()
    }

    private const val MAX_DEPTH = 64
    private const val MAX_CANDIDATES = 100
    private val COUNTRY_CODES = Locale.getISOCountries().map { it.lowercase(Locale.ROOT) }.toSet() + GeoRuleSets.bundled
    private val ADDRESS_FIELDS = setOf(
        "domain", "domain_suffix", "domain_keyword", "domain_regex", "ip_cidr", "ip_is_private", "geoip", "geosite",
    )
    private val DESTINATION_PORT_FIELDS = setOf("port", "port_range")
    private val SOURCE_ADDRESS_FIELDS = setOf("source_geoip", "source_ip_cidr", "source_ip_is_private")
    private val SOURCE_PORT_FIELDS = setOf("source_port", "source_port_range")
    private val META_FIELDS = setOf("type", "invert")
}
