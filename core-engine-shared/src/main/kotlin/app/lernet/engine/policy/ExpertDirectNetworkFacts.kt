package app.lernet.engine.policy

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

enum class DirectFamilyAvailability { AVAILABLE, LIMITED, UNAVAILABLE, UNKNOWN }

/** Local routing evidence. AVAILABLE never promises successful Internet access. */
data class ExpertDirectNetworkFacts(
    val ipv4: DirectFamilyAvailability,
    val ipv6: DirectFamilyAvailability,
    val source: String,
    val interfaceName: String?,
    val networkEpoch: Long,
    val observedAtMs: Long,
)

fun directNetworkFacts(body: JsonObject, observedAtMs: Long): ExpertDirectNetworkFacts? {
    val row = body["direct_families"] as? JsonObject ?: return null
    val epoch = (body["network_epoch"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        ?.takeIf { it >= 0 } ?: return null
    fun text(key: String): String? = (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun availability(key: String): DirectFamilyAvailability = when (text(key)) {
        "available" -> DirectFamilyAvailability.AVAILABLE
        "limited" -> DirectFamilyAvailability.LIMITED
        "unavailable" -> DirectFamilyAvailability.UNAVAILABLE
        else -> DirectFamilyAvailability.UNKNOWN
    }
    val source = text("source")?.takeIf { it in setOf("windows_routes", "platform_underlay_addresses") } ?: "unknown"
    return ExpertDirectNetworkFacts(
        availability("ipv4"), availability("ipv6"), source,
        text("interface")?.takeIf { it.length in 1..256 && it.none(Char::isISOControl) }, epoch, observedAtMs,
    )
}
