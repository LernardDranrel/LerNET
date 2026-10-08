package app.lernet.engine.policy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Bounded transition evidence for exported logs; never includes profile data or destinations. */
class ExpertStatusDiagnostics {
    private var lastSummary: String? = null
    private var lastAtMs: Long? = null

    @Synchronized
    fun update(body: JsonObject, underlayAvailable: Boolean, nowMs: Long): String? {
        val exits = (body["exits"] as? JsonArray).orEmpty()
        val folders = (body["folders"] as? JsonArray).orEmpty()
        val health = exits.take(10_000).mapNotNull { it as? JsonObject }.map { row ->
            val phase = string(row, "phase").takeIf {
                it in setOf("ready", "sleeping", "starting", "failed", "stopping", "closed")
            } ?: "unknown"
            val status = string(row, "health").takeIf {
                it in setOf("healthy", "degraded", "unknown", "checking")
            } ?: "unknown"
            "$phase/$status/${reason(string(row, "reason"))}"
        }.groupingBy { it }.eachCount().toSortedMap().entries.take(32)
            .joinToString(",") { "${it.key}:${it.value}" }
        val selected = folders.take(10_000).count {
            it is JsonObject && !string(it, "selected_tag").isNullOrBlank()
        }
        val summary = "native running=${(body["running"] as? JsonPrimitive)?.booleanOrNull} " +
            "revision=${(body["revision"] as? JsonPrimitive)?.longOrNull} " +
            "network_epoch=${(body["network_epoch"] as? JsonPrimitive)?.longOrNull} underlay=$underlayAvailable " +
            "stop=${reason(string(body, "stop_reason"))} " +
            "exits=${exits.size} [$health] folders_selected=$selected/${folders.size}"
        if (summary == lastSummary) return null
        // Polling/rapid network churn must not flood the session ring. Keep the last emitted
        // state so a suppressed change is emitted on a later poll if it persists.
        if (lastAtMs?.let { nowMs >= it && nowMs - it < 10_000 } == true) return null
        lastSummary = summary
        lastAtMs = nowMs
        return summary
    }

    private fun string(row: JsonObject, key: String): String? =
        (row[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun reason(value: String?): String = when {
        value.isNullOrBlank() -> "none"
        knownExpertNativeFailureExplanation(value) != null -> value
        value.matches(Regex("https_status_[1-5][0-9]{2}")) -> value
        value in setOf("tunnel_health_failed", "exit_sleeping", "operation_cancelled", "exit_transport_changed") -> value
        else -> "unclassified"
    }
}
