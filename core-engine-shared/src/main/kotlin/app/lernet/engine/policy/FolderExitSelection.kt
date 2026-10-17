package app.lernet.engine.policy

import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection

/** Only an HTTPS request through this exit can create a successful sample. TCP reachability is insufficient. */
data class ExitHealthSample(
    val profileId: String,
    val observedAtMs: Long,
    val httpsLatencyMs: Long?,
    val failedUntilMs: Long = 0,
)

sealed interface FolderExitDecision {
    data class Selected(val profileId: String) : FolderExitDecision

    /** Stale or sleeping exits need an explicit check; selection never pretends they are healthy. */
    data class CheckRequired(val profileIds: List<String>) : FolderExitDecision
    data object Unavailable : FolderExitDecision
}

object FolderExitSelection {
    fun select(
        policy: FolderPolicy,
        members: List<String>,
        samples: List<ExitHealthSample>,
        nowMs: Long,
        currentProfileId: String? = null,
        recovering: Boolean = false,
        lastSwitchAtMs: Long? = null,
    ): FolderExitDecision {
        require(nowMs >= 0)
        val order = members.distinct()
        if (order.isEmpty()) return FolderExitDecision.Unavailable
        val byId = samples.groupBy { it.profileId }.mapValues { (_, history) -> history.maxBy { it.observedAtMs } }
        fun fresh(id: String): Boolean {
            val sample = byId[id] ?: return false
            return sample.observedAtMs in 0..nowMs &&
                nowMs - sample.observedAtMs <= policy.freshnessMs &&
                sample.httpsLatencyMs?.let { it > 0 } == true &&
                nowMs >= sample.failedUntilMs
        }
        fun retryable(id: String): Boolean = byId[id]?.let { nowMs >= it.failedUntilMs } ?: true
        // Keep a healthy current exit until an actual failure. Only «самый быстрый» may leave it, and only
        // for a clear and rare gain (FastSwitchRule), so sessions do not churn between similar servers.
        if (!recovering && currentProfileId in order && currentProfileId != null && fresh(currentProfileId)) {
            if (policy.selection == FolderSelection.LOWEST_LATENCY && policy.autoSwap) {
                val currentMs = byId.getValue(currentProfileId).httpsLatencyMs!!
                val faster = order.filter { it != currentProfileId && fresh(it) }
                    .minByOrNull { byId.getValue(it).httpsLatencyMs!! }
                if (faster != null && FastSwitchRule.shouldSwitch(currentMs, byId.getValue(faster).httpsLatencyMs!!, lastSwitchAtMs, nowMs)) {
                    return FolderExitDecision.Selected(faster)
                }
            }
            return FolderExitDecision.Selected(currentProfileId)
        }
        val preferred = policy.preferredProfileId?.takeIf { it in order } ?: order.first()
        if (!policy.autoSwap && recovering) {
            val retry = currentProfileId?.takeIf { it in order } ?: preferred
            return if (retryable(retry)) FolderExitDecision.CheckRequired(listOf(retry)) else FolderExitDecision.Unavailable
        }
        if (!policy.autoSwap && policy.selection == FolderSelection.PREFERRED) {
            return if (fresh(preferred)) {
                FolderExitDecision.Selected(preferred)
            } else if (retryable(preferred)) {
                FolderExitDecision.CheckRequired(listOf(preferred))
            } else {
                FolderExitDecision.Unavailable
            }
        }
        // A recovery event invalidates the current exit even if its last successful sample is recent.
        val candidates = order.filter { fresh(it) && (!recovering || it != currentProfileId) }
        val selected = when (policy.selection) {
            FolderSelection.PREFERRED -> candidates.firstOrNull { it == preferred } ?: candidates.firstOrNull()
            FolderSelection.LOWEST_LATENCY -> candidates.minByOrNull { byId.getValue(it).httpsLatencyMs!! }
            FolderSelection.STABLE -> StableRank.order(candidates, samples, nowMs).firstOrNull()
        }
        if (selected != null) return FolderExitDecision.Selected(selected)
        val checks = order.filter(::retryable)
        return if (checks.isEmpty()) FolderExitDecision.Unavailable else FolderExitDecision.CheckRequired(checks)
    }
}
