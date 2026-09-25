package app.lernet.config.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Portable LerNET data. IDs are references inside the file, never database IDs on import. */
@Serializable
data class TransferBundle(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val scope: String,
    val groups: List<TransferGroup>,
    val profiles: List<TransferProfile>,
    val rules: List<TransferRule>,
    val selectedProfileId: String? = null,
) {
    companion object {
        const val FORMAT = "lernet-transfer"
        const val VERSION = 1
    }
}

@Serializable
data class TransferGroup(
    val id: String,
    val name: String,
    val profileIds: List<String>,
    val autoSwap: Boolean = false,
    val canvasLayout: String? = null,
)

@Serializable
data class TransferOutbound(val id: String, val tag: String, val type: String, val singBoxJson: String)

@Serializable
data class TransferProfile(
    val id: String,
    val name: String,
    val source: String,
    val outbounds: List<TransferOutbound>,
    val selectedOutboundId: String,
    val dnsJson: String? = null,
    val dnsPolicy: String = "UNDERLAY",
    val modeOverride: String? = null,
    val subscriptionUrl: String? = null,
    val canvasLayout: String? = null,
)

@Serializable
data class TransferPoint(val x: Float, val y: Float)

@Serializable
data class TransferRule(
    val id: String,
    val ownerId: String,
    val parentId: String? = null,
    val enabled: Boolean = true,
    val sortIndex: Int,
    val action: String,
    val pipeName: String = "",
    val title: String = "",
    val join: String = "AND",
    val apps: List<String> = emptyList(),
    val processes: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val domainSuffixes: List<String> = emptyList(),
    val ipCidrs: List<String> = emptyList(),
    val geoip: List<String> = emptyList(),
    val blocksJson: String = "",
    val position: TransferPoint? = null,
)

object TransferCodec {
    private const val MAX_CHARS = 20_000_000
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(bundle: TransferBundle): String {
        validate(bundle)
        val encoded = json.encodeToString(bundle)
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_CHARS) { "Архив LerNET больше 20 МБ" }
        return encoded
    }

    fun decode(raw: String): TransferBundle {
        require(raw.length <= MAX_CHARS) { "Архив LerNET больше 20 МБ" }
        val bundle = json.decodeFromString<TransferBundle>(raw)
        validate(bundle)
        return bundle
    }

    fun pointInLayout(raw: String?, ruleId: String): TransferPoint? = runCatching {
        val point = json.parseToJsonElement(raw ?: return null).jsonObject["rule:$ruleId"]?.jsonObject ?: return null
        TransferPoint(point.getValue("x").jsonPrimitive.content.toFloat(), point.getValue("y").jsonPrimitive.content.toFloat())
    }.getOrNull()

    fun remapCanvasLayout(raw: String?, ruleIds: Map<String, String>): String? {
        if (raw.isNullOrBlank()) return raw
        return runCatching {
            val source = json.parseToJsonElement(raw).jsonObject
            JsonObject(source.mapKeys { (key, _) ->
                if (key.startsWith("rule:")) "rule:${ruleIds[key.removePrefix("rule:")] ?: key.removePrefix("rule:")}" else key
            }).toString()
        }.getOrDefault(raw)
    }

    fun layoutFromPositions(rules: List<TransferRule>, ruleIds: Map<String, String>): String? {
        val positioned = rules.filter { it.position != null }
        if (positioned.isEmpty()) return null
        return buildJsonObject {
            positioned.forEach { rule ->
                val point = rule.position ?: return@forEach
                put("rule:${ruleIds.getValue(rule.id)}", buildJsonObject {
                    put("x", JsonPrimitive(point.x)); put("y", JsonPrimitive(point.y))
                })
            }
        }.toString()
    }

    fun validate(bundle: TransferBundle) {
        require(bundle.format == TransferBundle.FORMAT) { "Это не архив LerNET" }
        require(bundle.version == TransferBundle.VERSION) { "Неподдерживаемая версия архива: ${bundle.version}" }
        require(bundle.scope == "all" || bundle.scope == "group") { "Неизвестный тип архива" }
        require(bundle.groups.size <= 1_000 && bundle.profiles.size <= 10_000 && bundle.rules.size <= 100_000) {
            "Слишком много записей в архиве"
        }
        val groupIds = bundle.groups.map { it.id }
        val profileIds = bundle.profiles.map { it.id }
        val ruleIds = bundle.rules.map { it.id }
        require(groupIds.size == groupIds.toSet().size && profileIds.size == profileIds.toSet().size && ruleIds.size == ruleIds.toSet().size) {
            "Повторяющиеся идентификаторы в архиве"
        }
        require(bundle.groups.all { it.id.isNotBlank() && it.name.isNotBlank() }) { "Папка без названия" }
        require(bundle.profiles.all { profile ->
            profile.id.isNotBlank() && profile.name.isNotBlank() && profile.outbounds.isNotEmpty() &&
                profile.selectedOutboundId in profile.outbounds.map { it.id } &&
                profile.outbounds.map { it.id }.distinct().size == profile.outbounds.size
        }) { "Повреждён профиль в архиве" }
        val profileSet = profileIds.toSet()
        require(bundle.groups.flatMap { it.profileIds }.let { it.size == it.toSet().size && it.all(profileSet::contains) }) {
            "Папка ссылается на отсутствующий или повторный профиль"
        }
        val ownerIds = profileIds.toSet() + groupIds.map { "grp_$it" }
        val ruleById = bundle.rules.associateBy { it.id }
        require(bundle.rules.all { rule ->
            rule.id.isNotBlank() && rule.ownerId in ownerIds &&
                rule.action.uppercase() in setOf("PROXY", "DIRECT", "BLOCK") &&
                (rule.position == null || rule.position.x.isFinite() && rule.position.y.isFinite()) &&
                (rule.parentId == null || ruleById[rule.parentId]?.ownerId == rule.ownerId)
        }) { "Правило ссылается на отсутствующего владельца или родителя" }
        val settled = HashSet<String>()
        bundle.rules.forEach { rule ->
            val chain = HashSet<String>()
            var cursor: String? = rule.id
            while (cursor != null && cursor !in settled) {
                require(chain.add(cursor)) { "В правилах есть цикл" }
                cursor = ruleById[cursor]?.parentId
            }
            settled.addAll(chain)
        }
        require(bundle.selectedProfileId == null || bundle.selectedProfileId in profileIds) { "Выбранный профиль отсутствует" }
        if (bundle.scope == "group") require(bundle.groups.size == 1 && bundle.profiles.size == bundle.groups.single().profileIds.size) {
            "Архив папки должен содержать одну папку и её профили"
        }
    }
}
