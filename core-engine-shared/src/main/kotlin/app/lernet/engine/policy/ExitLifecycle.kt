package app.lernet.engine.policy

import app.lernet.routing.policy.ExitLifecyclePolicy

enum class ExitPhase { SLEEPING, STARTING, READY, DEGRADED, FAILED, DRAINING }

data class WakeTicket(val generation: Long)

sealed interface FlowAdmission {
    data object Ready : FlowAdmission
    data class Wake(val ticket: WakeTicket) : FlowAdmission
    data class Wait(val ticket: WakeTicket) : FlowAdmission
    data object QueueFull : FlowAdmission
    data object Unavailable : FlowAdmission
}

data class WakeCompletion(val admittedFlowIds: Set<String> = emptySet(), val rejectedFlowIds: Set<String> = emptySet())
data class ExitTick(
    val expiredFlowIds: Set<String> = emptySet(),
    val stopTicket: WakeTicket? = null,
    val cancelWakeTicket: WakeTicket? = null,
)
data class ExitLifecycleSnapshot(val phase: ExitPhase, val activeFlows: Int, val pendingFlows: Int)

/** One state machine per physical exit. The platform executes commands; this type never owns a TUN. */
class ExitLifecycle(private val policy: ExitLifecyclePolicy, startedAtMs: Long = 0) {
    @Volatile var phase: ExitPhase = ExitPhase.SLEEPING
        private set
    private var generation = 0L
    private var lastClockMs = startedAtMs
    private var lastUserActivityMs = startedAtMs
    private var wakeStartedAtMs = startedAtMs
    private val pending = linkedMapOf<String, Long>()
    private val active = mutableSetOf<String>()

    init {
        require(startedAtMs >= 0)
        require(policy.idleTimeoutMs > 0 && policy.firstFlowTimeoutMs > 0 && policy.startupTimeoutMs > 0 && policy.maxPendingFlows > 0)
    }

    @Synchronized
    fun request(flowId: String, nowMs: Long): FlowAdmission {
        clock(nowMs)
        require(flowId.isNotBlank())
        if (phase == ExitPhase.DRAINING || phase == ExitPhase.FAILED) return FlowAdmission.Unavailable
        if (flowId in active || phase == ExitPhase.READY) {
            active += flowId
            lastUserActivityMs = nowMs
            return FlowAdmission.Ready
        }
        if (flowId in pending) return FlowAdmission.Wait(WakeTicket(generation))
        if (pending.size >= policy.maxPendingFlows) return FlowAdmission.QueueFull
        pending[flowId] = nowMs
        lastUserActivityMs = nowMs
        return if (phase == ExitPhase.SLEEPING || phase == ExitPhase.DEGRADED) {
            phase = ExitPhase.STARTING
            wakeStartedAtMs = nowMs
            FlowAdmission.Wake(WakeTicket(++generation))
        } else {
            FlowAdmission.Wait(WakeTicket(generation))
        }
    }

    /** Warm startup is explicit and does not manufacture a user connection. */
    @Synchronized
    fun warm(nowMs: Long): WakeTicket? {
        clock(nowMs)
        if (phase !in setOf(ExitPhase.SLEEPING, ExitPhase.FAILED, ExitPhase.DEGRADED)) return null
        phase = ExitPhase.STARTING
        wakeStartedAtMs = nowMs
        lastUserActivityMs = nowMs
        return WakeTicket(++generation)
    }

    @Synchronized
    fun complete(ticket: WakeTicket, success: Boolean, nowMs: Long): WakeCompletion {
        clock(nowMs)
        if (ticket.generation != generation || phase != ExitPhase.STARTING) return WakeCompletion()
        val ready = success && nowMs - wakeStartedAtMs < policy.startupTimeoutMs
        val expired = pending.filterValues { nowMs - it >= policy.firstFlowTimeoutMs }.keys.toSet()
        val remaining = pending.keys - expired
        pending.clear()
        phase = if (ready) ExitPhase.READY else ExitPhase.FAILED
        if (ready) {
            active += remaining
            if (remaining.isNotEmpty()) lastUserActivityMs = nowMs
        }
        val rejected = expired + if (ready) emptySet() else remaining + active
        if (!ready) active.clear()
        return WakeCompletion(if (ready) remaining else emptySet(), rejected)
    }

    @Synchronized
    fun traffic(flowId: String, nowMs: Long) {
        clock(nowMs)
        if (flowId in active) lastUserActivityMs = nowMs
    }

    @Synchronized
    fun closeFlow(flowId: String, nowMs: Long) {
        clock(nowMs)
        if (active.remove(flowId) || pending.remove(flowId) != null) lastUserActivityMs = nowMs
    }

    /** Health probes do not wake an exit and do not extend its inactivity timer. */
    @Synchronized
    fun health(success: Boolean, nowMs: Long) {
        clock(nowMs)
        if (phase == ExitPhase.READY && !success) {
            phase = ExitPhase.DEGRADED
        } else if (phase == ExitPhase.DEGRADED && success) {
            phase = ExitPhase.READY
        }
    }

    @Synchronized
    fun tick(nowMs: Long): ExitTick {
        clock(nowMs)
        val expired = pending.filterValues { nowMs - it >= policy.firstFlowTimeoutMs }.keys.toSet()
        expired.forEach(pending::remove)
        if (phase == ExitPhase.STARTING && nowMs - wakeStartedAtMs >= policy.startupTimeoutMs) {
            val rejected = expired + pending.keys + active
            pending.clear()
            active.clear()
            phase = ExitPhase.FAILED
            val cancelled = WakeTicket(generation++)
            return ExitTick(rejected, cancelWakeTicket = cancelled)
        }
        val idle = policy.coldStart &&
            phase in setOf(ExitPhase.READY, ExitPhase.DEGRADED) &&
            active.isEmpty() &&
            pending.isEmpty() &&
            nowMs - lastUserActivityMs >= policy.idleTimeoutMs
        if (idle) {
            phase = ExitPhase.DRAINING
            return ExitTick(expired, WakeTicket(++generation))
        }
        return ExitTick(expired)
    }

    @Synchronized
    fun stopped(ticket: WakeTicket, nowMs: Long, success: Boolean = true) {
        clock(nowMs)
        if (ticket.generation == generation && phase == ExitPhase.DRAINING) phase = if (success) ExitPhase.SLEEPING else ExitPhase.FAILED
    }

    @Synchronized
    fun resetFailed(nowMs: Long) {
        clock(nowMs)
        if (phase == ExitPhase.FAILED) phase = ExitPhase.SLEEPING
    }

    /** Network changes invalidate a pending startup; its later completion cannot admit stale queued flows. */
    @Synchronized
    fun invalidateStartup(nowMs: Long): Set<String> {
        clock(nowMs)
        if (phase != ExitPhase.STARTING) return emptySet()
        generation++
        phase = ExitPhase.FAILED
        val rejected = pending.keys.toSet()
        pending.clear()
        return rejected
    }

    @Synchronized
    fun snapshot(): ExitLifecycleSnapshot = ExitLifecycleSnapshot(phase, active.size, pending.size)

    /** A manual sleep must not silently kill quiet user connections. */
    @Synchronized
    fun requestSleep(nowMs: Long): WakeTicket? {
        clock(nowMs)
        if (phase !in setOf(ExitPhase.READY, ExitPhase.DEGRADED) || active.isNotEmpty() || pending.isNotEmpty()) return null
        phase = ExitPhase.DRAINING
        return WakeTicket(++generation)
    }

    private fun clock(nowMs: Long) {
        require(nowMs >= lastClockMs) { "Lifecycle requires a monotonic clock" }
        lastClockMs = nowMs
    }
}
