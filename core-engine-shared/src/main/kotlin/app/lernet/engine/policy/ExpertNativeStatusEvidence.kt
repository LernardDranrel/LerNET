package app.lernet.engine.policy

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Bounded native evidence; provider error strings are never accepted as cleanup reasons. */
object ExpertNativeStatusEvidence {
    fun exitPhase(phase: String?, health: String?): ExitPhase {
        val resource = when (phase?.uppercase(Locale.ROOT)) {
            "STOPPING" -> ExitPhase.DRAINING
            else -> ExitPhase.entries.firstOrNull { it.name == phase?.uppercase(Locale.ROOT) } ?: ExitPhase.FAILED
        }
        return if (resource == ExitPhase.READY && health == "degraded") ExitPhase.DEGRADED else resource
    }

    fun retiredCleanupFailures(body: JsonObject): List<ExpertRetiredCleanupFailure> =
        (body["retired_cleanup_failures"] as? JsonArray).orEmpty().asSequence().take(LIMIT).mapNotNull { item ->
            val row = item as? JsonObject ?: return@mapNotNull null
            val revisionValue = row["revision"] as? JsonPrimitive ?: return@mapNotNull null
            val revision = revisionValue.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
                ?: return@mapNotNull null
            val rawReason = (row["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val tags = (row["exit_tags"] as? JsonArray).orEmpty().asSequence().take(LIMIT).mapNotNull { value ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(LIMIT)
            }.distinct().sorted().toList()
            ExpertRetiredCleanupFailure(revision, cleanupReasonCode(rawReason), tags)
        }.distinct().toList()

    internal fun cleanupReasonCode(value: String?): String =
        value?.takeIf { it in REASON_CODES } ?: "expert_cleanup_pending"

    private const val LIMIT = 128
    private val REASON_CODES = setOf("expert_cleanup_pending", "exit_stop_failed", "generation_stop_failed")
}
