package app.lernet.config.subscription

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.parse.ImportedProfileDraft
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What a config looked like when the subscription last wrote it, to tell the user's own edits apart. */
@Serializable
data class SubscriptionOrigin(val name: String, val hash: String)

/** One config of a subscription as the platform stores it now. The remote list owns it. */
data class SubscribedConfig(
    val id: String,
    val name: String,
    val outbounds: List<NormalizedOutbound>,
    val selectedOutboundId: String,
) {
    val selectedOutbound: NormalizedOutbound? get() = outbounds.firstOrNull { it.id == selectedOutboundId } ?: outbounds.firstOrNull()
}

/** New server data for a config that stays (same id, so its rules, folder and selection stay too). */
data class ConfigUpdate(
    val id: String,
    /** New name, or null when the remote name matches what is already stored. */
    val name: String?,
    val outbounds: List<NormalizedOutbound>,
    val selectedOutboundId: String,
)

data class RefreshPlan(
    val updates: List<ConfigUpdate> = emptyList(),
    val adds: List<ImportedProfileDraft> = emptyList(),
    val removes: List<String> = emptyList(),
    /** Removal was skipped as a whole: the answer is much shorter than before (an error page, an expired plan). */
    val removalHeld: Boolean = false,
    /** Origins of the configs that stay, by id. Added configs get theirs from [originOf] once they have an id. */
    val origins: Map<String, SubscriptionOrigin> = emptyMap(),
    val unchanged: Int = 0,
) {
    val changed: Int get() = updates.size

    /** One line for the screen: what happened, in plain words. */
    fun summary(skippedText: String?): String = buildList {
        add(
            if (updates.isEmpty() && adds.isEmpty() && removes.isEmpty()) "Подписка обновлена, изменений нет"
            else "Подписка обновлена: изменено ${updates.size}, добавлено ${adds.size}, удалено ${removes.size}",
        )
        if (removalHeld) add("подписка вернула намного меньше серверов, чем было, поэтому старые конфиги не удалены")
        if (updates.isNotEmpty()) add("изменения вступят в силу при следующем подключении")
        skippedText?.let(::add)
    }.joinToString(". ") { part -> part.replaceFirstChar { it.uppercaseChar() } }.trimEnd('.') + "."
}

/**
 * Plans a subscription refresh without touching storage, the same way on Android and Windows.
 * The folder mirrors the remote list: match by name+server, then name, then server; keep ids;
 * take remote name and server data; add new servers; remove gone ones (unless the answer is
 * much shorter than before — then removal is held as a whole).
 */
object SubscriptionRefresh {
    private val json = Json { ignoreUnknownKeys = true }

    fun plan(
        existing: List<SubscribedConfig>,
        fresh: List<ImportedProfileDraft>,
        origins: Map<String, SubscriptionOrigin>,
    ): RefreshPlan {
        val freshLeft = fresh.indices.toMutableList()
        val matches = mutableMapOf<String, Int>()
        fun pass(key: (Int) -> String?, keyOfExisting: (SubscribedConfig) -> String?) {
            existing.filter { it.id !in matches }.forEach { config ->
                val wanted = keyOfExisting(config) ?: return@forEach
                val candidates = freshLeft.filter { key(it) == wanted }
                if (candidates.size == 1 || (candidates.isNotEmpty() && existing.count { it.id !in matches && keyOfExisting(it) == wanted } == 1)) {
                    val chosen = candidates.first()
                    matches[config.id] = chosen
                    freshLeft.remove(chosen)
                }
            }
        }
        val freshEndpoint = fresh.map { endpointOf(it.outbounds.firstOrNull { o -> o.id == it.selectedOutboundId } ?: it.outbounds.firstOrNull()) }
        fun originName(config: SubscribedConfig) = origins[config.id]?.name ?: config.name
        pass({ "${fresh[it].name}|${freshEndpoint[it]}" }) { "${originName(it)}|${endpointOf(it.selectedOutbound)}" }
        pass({ fresh[it].name }) { originName(it) }
        pass({ freshEndpoint[it] }) { endpointOf(it.selectedOutbound) }

        val updates = mutableListOf<ConfigUpdate>()
        val newOrigins = mutableMapOf<String, SubscriptionOrigin>()
        var unchanged = 0
        existing.forEach { config ->
            val index = matches[config.id] ?: return@forEach
            val draft = fresh[index]
            val freshHash = hashOf(draft.outbounds)
            val sameName = draft.name == config.name
            if (freshHash == hashOf(config.outbounds) && sameName) {
                unchanged++
                newOrigins[config.id] = SubscriptionOrigin(draft.name, freshHash)
                return@forEach
            }
            // The chosen server keeps its id, so a folder strategy pointing at it stays valid.
            val freshSelected = draft.outbounds.firstOrNull { it.id == draft.selectedOutboundId } ?: draft.outbounds.first()
            val currentSelected = config.selectedOutbound
            val outbounds = draft.outbounds.map { outbound ->
                if (outbound.id == freshSelected.id && currentSelected != null) outbound.copy(id = currentSelected.id) else outbound
            }
            val selectedId = currentSelected?.id ?: freshSelected.id
            updates += ConfigUpdate(config.id, if (sameName) null else draft.name, outbounds, selectedId)
            newOrigins[config.id] = SubscriptionOrigin(draft.name, freshHash)
        }

        val gone = existing.filter { it.id !in matches }
        // An answer under half the old size is more likely an error page or an expired plan than a real change.
        val removalHeld = gone.isNotEmpty() && fresh.size * 2 < existing.size
        val removes = if (removalHeld) emptyList() else gone.map { it.id }
        if (removalHeld) {
            gone.forEach { config -> origins[config.id]?.let { newOrigins[config.id] = it } }
        }
        return RefreshPlan(
            updates = updates,
            adds = freshLeft.map { fresh[it] },
            removes = removes,
            removalHeld = removalHeld,
            origins = newOrigins,
            unchanged = unchanged,
        )
    }

    /** Null when this address has no configs yet — the platform should do a first import instead. */
    fun planIfPresent(
        existing: List<SubscribedConfig>,
        fresh: List<ImportedProfileDraft>,
        origins: Map<String, SubscriptionOrigin>,
    ): RefreshPlan? = if (existing.isEmpty()) null else plan(existing, fresh, origins)

    /** Origin to remember for a config the refresh has just added. */
    fun originOf(draft: ImportedProfileDraft): SubscriptionOrigin = SubscriptionOrigin(draft.name, hashOf(draft.outbounds))

    /** Stable fingerprint of server data: JSON with keys in order, without the outbound tag. */
    fun hashOf(outbounds: List<NormalizedOutbound>): String {
        val text = outbounds.joinToString("\n") { canonical(it.singBoxJson) }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun canonical(raw: String): String {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return raw
        return sorted(element, top = true).toString()
    }

    private fun sorted(element: kotlinx.serialization.json.JsonElement, top: Boolean = false): kotlinx.serialization.json.JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterKeys { !(top && it == "tag") }.toSortedMap().mapValues { sorted(it.value) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(element.map { sorted(it) })
        else -> element
    }

    private fun endpointOf(outbound: NormalizedOutbound?): String? {
        outbound ?: return null
        val obj = runCatching { json.parseToJsonElement(outbound.singBoxJson).jsonObject }.getOrNull() ?: return null
        val server = obj["server"]?.jsonPrimitive?.contentOrNull ?: return null
        val port = obj["server_port"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return "${outbound.type}|${server.lowercase()}|$port"
    }

}
