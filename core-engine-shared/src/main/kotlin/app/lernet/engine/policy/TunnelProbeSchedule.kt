package app.lernet.engine.policy

import kotlin.random.Random

/**
 * When to actively check the tunnel. The check is rare and only has a reason: right after the
 * tunnel comes up, after the network changed, or when live traffic looks wrong (we keep sending
 * through the tunnel but no replies come back for [silenceMs]). There is no fixed beat: a fixed
 * 5–10 s rhythm is itself visible on the wire.
 *
 * A failed check is confirmed once after a random 3–8 s pause (no bursts of retries 2 s apart).
 * Two failures in a row mean the tunnel is broken. After a successful check the next one waits
 * a random 30–90 s cooldown, even if traffic stays odd. A network change skips the cooldown.
 *
 * Pure logic on a caller-supplied clock, shared by Android and Windows.
 */
class TunnelProbeSchedule(
    private val firstMs: () -> Long = { Random.nextLong(3_000L, 8_001L) },
    private val confirmMs: () -> Long = { Random.nextLong(3_000L, 8_001L) },
    private val cooldownMs: () -> Long = { Random.nextLong(30_000L, 90_001L) },
    private val silenceMs: Long = SILENCE_MS,
    private val minUnansweredUpBytes: Long = MIN_UNANSWERED_UP_BYTES,
    private val failuresToFail: Int = 2,
) {
    enum class Verdict { HEALTHY, CONFIRM, FAILED }

    enum class Reason { START, NETWORK, SILENCE, CONFIRM }

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
        /** More than a couple of TCP keep-alives: real requests that stayed unanswered. */
        const val MIN_UNANSWERED_UP_BYTES = 1_500L
    }
}
