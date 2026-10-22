package app.lernet.engine.policy

import kotlin.random.Random

/**
 * When to actively check the tunnel. The check is rare and only has a reason: right after the
 * tunnel comes up, after the network changed, or when live traffic looks wrong (we keep sending
 * through the tunnel but no replies come back for [silenceMs]). There is no fixed beat: a fixed
 * 5–10 s rhythm is itself visible on the wire.
 *
 * Each check may take up to [CHECK_TIMEOUT_MS] per step: under jamming a fresh handshake takes 5–20 s,
 * and a 5 s limit declared live but slow tunnels dead. A failed check is confirmed once after a random
 * 10–20 s pause. Two failures in a row mean the tunnel is broken. After a successful check the next one
 * waits a random 30–90 s cooldown, even if traffic stays odd. A network change skips the cooldown.
 * A hint (missed DNS, silent watchdog, the block detector) asks for a check but respects the cooldown.
 * A check that does not fit the handshake budget is moved, not dropped ([defer]).
 *
 * Pure logic on a caller-supplied clock, shared by Android and Windows.
 */
class TunnelProbeSchedule(
    private val firstMs: () -> Long = { Random.nextLong(3_000L, 8_001L) },
    private val confirmMs: () -> Long = { Random.nextLong(10_000L, 20_001L) },
    private val cooldownMs: () -> Long = { Random.nextLong(30_000L, 90_001L) },
    private val silenceMs: Long = SILENCE_MS,
    private val minUnansweredUpBytes: Long = MIN_UNANSWERED_UP_BYTES,
    private val failuresToFail: Int = 2,
) {
    enum class Verdict { HEALTHY, CONFIRM, FAILED }

    enum class Reason { START, NETWORK, SILENCE, CONFIRM, HINT, HOLD }

    private var nextAt: Long? = null
    private var nextReason: Reason? = null
    private var cooldownUntil = 0L
    private var inFlight = false
    private var failures = 0

    private var trafficSeen = false
    private var lastReplyCounter = 0L
    private var lastReplyAt = 0L
    private var upAtLastReply = 0L

    val failureCount: Int get() = failures

    /** The tunnel just came up: one confirming check after a short random pause. */
    fun onStart(nowMs: Long) {
        failures = 0
        inFlight = false
        cooldownUntil = 0L
        trafficSeen = false
        plan(nowMs + firstMs(), Reason.START)
    }

    /** The underlying network changed: check soon, ignoring the cooldown. */
    fun onNetworkChanged(nowMs: Long) {
        trafficSeen = false
        cooldownUntil = 0L
        plan(nowMs + firstMs(), Reason.NETWORK)
    }

    /** Something looked wrong (DNS, watchdog, block detector): one check soon, unless one just passed. */
    fun onHint(nowMs: Long) {
        if (inFlight || nowMs < cooldownUntil) return
        plan(nowMs, Reason.HINT)
    }

    /**
     * The tunnel is held after a failed round: look again after [delayMs], and not sooner for silence or a
     * hint (both would only repeat what the round already showed). A network change still checks soon.
     */
    fun planHold(nowMs: Long, delayMs: Long) {
        failures = 0
        cooldownUntil = nowMs + delayMs
        nextAt = nowMs + delayMs
        nextReason = Reason.HOLD
    }

    /** A due check could not run now (handshake budget): run it at [atMs] for the same reason. */
    fun defer(reason: Reason, atMs: Long) {
        inFlight = false
        val current = nextAt
        if (current == null || atMs < current) {
            nextAt = atMs
            nextReason = reason
        }
    }

    /**
     * Live counters. [replyCounter] must only grow when replies come back through the tunnel
     * (proxied reply count, or the tunnel's received bytes when per-connection data is missing).
     */
    fun onTraffic(nowMs: Long, uplinkTotal: Long, replyCounter: Long) {
        if (!trafficSeen || replyCounter > lastReplyCounter || uplinkTotal < upAtLastReply) {
            trafficSeen = true
            lastReplyCounter = replyCounter
            lastReplyAt = nowMs
            upAtLastReply = uplinkTotal
            return
        }
        val unanswered = uplinkTotal - upAtLastReply
        if (unanswered >= minUnansweredUpBytes && nowMs - lastReplyAt >= silenceMs &&
            nowMs >= cooldownUntil && !inFlight && nextAt == null
        ) {
            plan(nowMs, Reason.SILENCE)
        }
    }

    /** Returns the reason when a check is due now; the caller then runs exactly one check. */
    fun takeDue(nowMs: Long): Reason? {
        val at = nextAt ?: return null
        if (inFlight || nowMs < at) return null
        val reason = nextReason
        nextAt = null
        nextReason = null
        inFlight = true
        return reason
    }

    fun onResult(nowMs: Long, ok: Boolean): Verdict {
        inFlight = false
        if (ok) {
            failures = 0
            cooldownUntil = nowMs + cooldownMs()
            // Fresh baseline: the check's own reply must not mask later silence.
            trafficSeen = false
            return Verdict.HEALTHY
        }
        failures++
        if (failures >= failuresToFail) {
            failures = 0
            // The owner decides what comes next (checks of backups, a hold); no new look meanwhile.
            cooldownUntil = nowMs + cooldownMs()
            return Verdict.FAILED
        }
        plan(nowMs + confirmMs(), Reason.CONFIRM)
        return Verdict.CONFIRM
    }

    fun nextCheckAt(): Long? = nextAt

    private fun plan(atMs: Long, reason: Reason) {
        val current = nextAt
        if (current == null || atMs < current) {
            nextAt = atMs
            nextReason = reason
        }
    }

    companion object {
        const val SILENCE_MS = 8_000L

        /** Connect and read limit of one tunnel check: a jammed handshake takes 5–20 s. */
        const val CHECK_TIMEOUT_MS = 15_000L

        /** Whole-request limit of one tunnel check. */
        const val CALL_TIMEOUT_MS = 20_000L

        /** More than a couple of TCP keep-alives: real requests that stayed unanswered. */
        const val MIN_UNANSWERED_UP_BYTES = 1_500L
    }
}
