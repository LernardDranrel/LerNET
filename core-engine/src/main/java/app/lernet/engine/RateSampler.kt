package app.lernet.engine

data class SampledRate(
    val uplinkBps: Long,
    val downlinkBps: Long,
)

/**
 * CommandStatus uplink/downlink stay 0 while the totals move (phone
 * `2e1e0df6073d`: upTotal 3537→8050, printed rate 0 B/s). Derive a rate from
 * the total delta. A frozen total stays 0 — do not invent download progress.
 */
class RateSampler {
    private var previousUp: Long = 0L
    private var previousDown: Long = 0L
    private var previousAtMs: Long = 0L
    private var primed: Boolean = false

    fun reset() {
        previousUp = 0L
        previousDown = 0L
        previousAtMs = 0L
        primed = false
    }

    fun sample(
        reportedUpBps: Long,
        reportedDownBps: Long,
        uplinkTotal: Long,
        downlinkTotal: Long,
        nowMs: Long,
    ): SampledRate {
        val up = direction(reportedUpBps, uplinkTotal, previousUp, nowMs)
        val down = direction(reportedDownBps, downlinkTotal, previousDown, nowMs)
        previousUp = uplinkTotal
        previousDown = downlinkTotal
        previousAtMs = nowMs
        primed = true
        return SampledRate(uplinkBps = up, downlinkBps = down)
    }

    private fun direction(reported: Long, total: Long, previous: Long, nowMs: Long): Long {
        if (reported > 0L) return reported
        if (!primed) return 0L
        val elapsed = nowMs - previousAtMs
        if (elapsed <= 0L) return 0L
        val delta = total - previous
        if (delta <= 0L) return 0L
        val perSecond = delta * MILLIS_PER_SECOND / elapsed
        return if (perSecond < 1L) 1L else perSecond
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
