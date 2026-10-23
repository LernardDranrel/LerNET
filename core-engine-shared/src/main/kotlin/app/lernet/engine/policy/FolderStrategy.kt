package app.lernet.engine.policy

import kotlin.random.Random

/**
 * Legacy thresholds of the removed «Самый быстрый» strategy. Kept for reading old manifests; selection
 * no longer leaves a healthy exit for speed (ТСПУ / «только мёртвый»).
 */
@Deprecated("«Самый быстрый» removed; folder selection migrates to STABLE.")
object FastSwitchRule {
    const val MIN_GAIN_MS = 40L
    const val MIN_GAIN_PERCENT = 25L
    const val MIN_INTERVAL_MS = 10 * 60_000L

    fun shouldSwitch(currentMs: Long, candidateMs: Long, lastSwitchAtMs: Long?, nowMs: Long): Boolean {
        if (currentMs <= 0 || candidateMs <= 0) return false
        val gain = currentMs - candidateMs
        if (gain < MIN_GAIN_MS || gain * 100 < currentMs * MIN_GAIN_PERCENT) return false
        return lastSwitchAtMs == null || nowMs - lastSwitchAtMs >= MIN_INTERVAL_MS
    }
}

/**
 * «Самый стабильный»: rank exits by how often their recent checks succeeded, not by speed.
 * A healthy current exit is never left for a "more stable" one; the rank only orders the search
 * after a failure. Unknown exits sit in the middle (score 0.5), so a fresh server is tried before
 * one that keeps failing but after one with a clean record.
 */
object StableRank {
    const val WINDOW_MS = 30 * 60_000L

    fun score(history: List<ExitHealthSample>, nowMs: Long): Double {
        val recent = history.filter { nowMs - it.observedAtMs in 0..WINDOW_MS }
        if (recent.isEmpty()) return 0.5
        val ok = recent.count { it.httpsLatencyMs?.let { ms -> ms > 0 } == true }
        // One success out of one is not better than nine out of ten: a weak prior of one each.
        return (ok + 1.0) / (recent.size + 2.0)
    }

    fun order(members: List<String>, samples: List<ExitHealthSample>, nowMs: Long): List<String> {
        val byId = samples.groupBy { it.profileId }
        return members.distinct().withIndex()
            .sortedWith(compareByDescending<IndexedValue<String>> { score(byId[it.value].orEmpty(), nowMs) }.thenBy { it.index })
            .map { it.value }
    }
}

/**
 * Background checks of backup configs: one at a time, at a random 45 s – 5 min pace, in rotation.
 * Always on, also on mobile data: a backup that is known to work lets failover switch in seconds.
 */
class BackupRotation {
    private var last: String? = null

    /** The next backup to check, skipping the active one; null when there is no backup. */
    fun next(members: List<String>, activeId: String?): String? {
        val backups = members.distinct().filter { it != activeId }
        if (backups.isEmpty()) return null
        val after = last?.let(backups::indexOf) ?: -1
        return backups[(after + 1) % backups.size].also { last = it }
    }

    fun reset() {
        last = null
    }

    companion object {
        fun nextDelayMs(random: Random = Random.Default): Long = RecheckJitter.nextMs(random)

        /**
         * Pause between two checks of the «проверить все» button: one by one with a random 1.5–4 s gap,
         * not a burst. Checks through the tunnel also wait for the active server's [HandshakeBudget].
         */
        fun buttonGapMs(random: Random = Random.Default): Long = random.nextLong(BUTTON_GAP_MIN_MS, BUTTON_GAP_MAX_MS + 1)

        const val BUTTON_GAP_MIN_MS = 1_500L
        const val BUTTON_GAP_MAX_MS = 4_000L
    }
}

/** What a backup check found; fresh only while the network stays the same. */
data class BackupHealth(val ok: Boolean, val latencyMs: Long?, val checkedAtMs: Long, val throughTunnel: Boolean) {
    fun freshAt(nowMs: Long): Boolean = nowMs - checkedAtMs in 0..FRESH_MS

    companion object {
        const val FRESH_MS = 5 * 60_000L

        /** Failover order: backups verified recently first, then unknown, then recently failed. */
        fun order(candidates: List<String>, health: Map<String, BackupHealth>, nowMs: Long): List<String> =
            candidates.withIndex().sortedBy { (index, id) ->
                val h = health[id]?.takeIf { it.freshAt(nowMs) }
                val rank = when {
                    h == null -> 1
                    h.ok -> 0
                    else -> 2
                }
                rank * 10_000 + index
            }.map { it.value }
    }
}

enum class ReachRoute { TUNNEL, DIRECT, SKIP }

/**
 * How to check a backup server.
 *
 * A CONNECT through the *live* tunnel to another config's host only proves the active path still
 * works — it says nothing about that backup. So when the tunnel is up we do not use it for
 * candidate/«Проверить» checks: background skips, a user press dials the host directly (outside
 * the tunnel). «Через VPN» for a non-active config is a conscious full check of that config
 * (LinkCheck.DIAGNOSTIC), not a ride on the current tunnel. Failover still dials the chosen
 * backup directly before switching.
 */
object BackupReach {
    /** @param tcp kept for shared call sites; UDP cannot CONNECT-preflight either way. */
    @Suppress("UNUSED_PARAMETER")
    fun route(tunnelUp: Boolean, tcp: Boolean, userAsked: Boolean): ReachRoute = when {
        tunnelUp && !userAsked -> ReachRoute.SKIP
        else -> ReachRoute.DIRECT
    }
}
