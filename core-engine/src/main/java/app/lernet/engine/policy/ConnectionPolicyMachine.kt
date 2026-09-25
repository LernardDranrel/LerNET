package app.lernet.engine.policy

import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.FailoverBanner
import app.lernet.engine.RunMode

data class MachineContext(
    val reconnect: ReconnectSettings,
    val failover: FailoverSettings,
    val group: ManualFailoverGroup?,
)

data class MachineState(
    val snapshot: ConnectionSnapshot,
    val consecutiveFailures: Int,
    val knownDead: Set<String>,
    val softReloads: Int = 0,
) {
    companion object {
        fun idle(mode: RunMode = RunMode.FULL_VPN): MachineState =
            MachineState(ConnectionSnapshot.idle(mode), consecutiveFailures = 0, knownDead = emptySet())
    }
}

class ConnectionPolicyMachine {
    fun reduce(
        state: MachineState,
        context: MachineContext,
        event: PolicyEvent,
    ): Pair<MachineState, List<PolicyCommand>> = when (event) {
        is PolicyEvent.StartRequested -> start(state, event)
        PolicyEvent.EngineStarted -> accepted(state)
        is PolicyEvent.EngineStatus -> onStatus(state, event)
        is PolicyEvent.EngineFailed -> onFailure(state, context, event.cause)
        PolicyEvent.WatchdogMiss -> onWatchdog(state, context)
        PolicyEvent.DnsHealthTimeout -> onDnsHealth(state, context)
        PolicyEvent.DnsPathOk -> onDnsPathOk(state)
        PolicyEvent.ConnectTimeout -> onConnectTimeout(state, context)
        PolicyEvent.OutboundReady -> onOutboundReady(state)
        PolicyEvent.UserDisconnect -> userDisconnect(state)
        is PolicyEvent.ProbeCompleted -> onProbe(state, context, event.aliveOutboundIds)
        PolicyEvent.PermissionDenied ->
            failNow(state, ConnectionCause.VpnPermissionDenied)
    }

    private fun start(state: MachineState, event: PolicyEvent.StartRequested): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.CONNECTING,
            cause = null,
            mode = event.mode,
            activeProfileId = event.profileId,
            activeOutboundId = event.outboundId,
            attempt = 0,
            banner = state.snapshot.banner.takeIf {
                state.snapshot.state == ConnectionState.CONNECTING && state.snapshot.activeOutboundId == event.outboundId
            },
            compiledJson = event.compiledJson,
            uplinkBps = 0,
            downlinkBps = 0,
            uplinkTotal = 0,
            downlinkTotal = 0,
            dnsOk = false,
        )
        return MachineState(snapshot, consecutiveFailures = 0, knownDead = emptySet(), softReloads = 0) to
            listOf(PolicyCommand.StartEngine(event.outboundId))
    }

    private fun accepted(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        if (isTerminal(state)) {
            val cause = state.snapshot.cause ?: ConnectionCause.UserDisconnected
            return state to listOf(PolicyCommand.StopEngine(cause))
        }
        return state to emptyList()
    }

    private fun onOutboundReady(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        if (isTerminal(state)) {
            return state to emptyList()
        }
        val waiting = state.snapshot.state == ConnectionState.CONNECTING ||
            state.snapshot.state == ConnectionState.RECONNECTING
        return if (waiting) connected(state) else state to emptyList()
    }

    private fun onStatus(
        state: MachineState,
        event: PolicyEvent.EngineStatus,
    ): Pair<MachineState, List<PolicyCommand>> {
        if (isTerminal(state)) {
            return state to emptyList()
        }
        val snapshot = state.snapshot.copy(
            uplinkBps = event.uplinkBps,
            downlinkBps = event.downlinkBps,
            uplinkTotal = event.uplinkTotal,
            downlinkTotal = event.downlinkTotal,
            dnsOk = event.dnsOk || state.snapshot.dnsOk,
        )
        // A real reply after recovery starts a fresh health window. Merely sending bytes
        // upstream must not clear a silent-tunnel warning.
        val recovered = event.downlinkTotal > state.snapshot.downlinkTotal
        return state.copy(
            snapshot = snapshot,
            softReloads = if (recovered) 0 else state.softReloads,
            consecutiveFailures = if (recovered) 0 else state.consecutiveFailures,
        ) to emptyList()
    }

    private fun onConnectTimeout(
        state: MachineState,
        context: MachineContext,
    ): Pair<MachineState, List<PolicyCommand>> {
        if (isTerminal(state) || state.snapshot.state == ConnectionState.CONNECTED) {
            return state to emptyList()
        }
        return onFailure(
            state,
            context,
            ConnectionCause.ConnectTimeout(
                (context.reconnect.connectTimeoutMs / 1000L).toInt().coerceAtLeast(1),
            ),
        )
    }

    private fun onWatchdog(
        state: MachineState,
        context: MachineContext,
    ): Pair<MachineState, List<PolicyCommand>> {
        val current = state.snapshot.state
        val connectSeconds = (context.reconnect.connectTimeoutMs / 1000L).toInt().coerceAtLeast(1)
        val silentSeconds = (context.reconnect.watchdogTimeoutMs / 1000L).toInt().coerceAtLeast(1)
        return when (current) {
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
            -> onFailure(state, context, ConnectionCause.ConnectTimeout(connectSeconds))
            ConnectionState.CONNECTED -> onConnectedHealth(state, context, ConnectionCause.WatchdogTimeout(silentSeconds))
            ConnectionState.DISCONNECTED,
            ConnectionState.FAILED,
            -> state to emptyList()
        }
    }

    private fun onDnsPathOk(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        if (isTerminal(state)) {
            return state to emptyList()
        }
        return state.copy(snapshot = state.snapshot.copy(dnsOk = true)) to emptyList()
    }

    private fun onDnsHealth(
        state: MachineState,
        context: MachineContext,
    ): Pair<MachineState, List<PolicyCommand>> {
        if (state.snapshot.state != ConnectionState.CONNECTED) {
            return state to emptyList()
        }
        val seconds = (DnsHealthGate.DEADLINE_MS / 1000L).toInt().coerceAtLeast(1)
        val first = ConnectionCause.DnsStalled(seconds)
        val dead = ConnectionCause.DnsUnreachable(seconds)
        return onConnectedHealth(state, context, if (state.softReloads == 0) first else dead)
    }

    private fun onConnectedHealth(
        state: MachineState,
        context: MachineContext,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        if (state.snapshot.state != ConnectionState.CONNECTED) {
            return state to emptyList()
        }
        val (next, commands) = onFailure(state, context, cause)
        return next.copy(softReloads = state.softReloads + 1) to commands
    }

    private fun connected(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.CONNECTED,
            cause = null,
            attempt = 0,
        )
        return state.copy(snapshot = snapshot) to emptyList()
    }

    private fun userDisconnect(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        if (state.snapshot.state == ConnectionState.DISCONNECTED) {
            return state to emptyList()
        }
        return disconnect(state, ConnectionCause.UserDisconnected)
    }

    private fun isTerminal(state: MachineState): Boolean {
        val current = state.snapshot.state
        return current == ConnectionState.DISCONNECTED || current == ConnectionState.FAILED
    }

    private fun disconnect(
        state: MachineState,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.DISCONNECTED,
            cause = cause,
            attempt = 0,
            compiledJson = null,
        )
        return MachineState(snapshot, 0, emptySet()) to listOf(PolicyCommand.StopEngine(cause))
    }

    private fun failNow(
        state: MachineState,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.FAILED,
            cause = cause,
            compiledJson = null,
        )
        return state.copy(snapshot = snapshot) to listOf(PolicyCommand.StopEngine(cause))
    }

    private fun onFailure(
        state: MachineState,
        context: MachineContext,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        if (state.snapshot.state == ConnectionState.FAILED) {
            return state to emptyList()
        }
        if (state.snapshot.state == ConnectionState.DISCONNECTED &&
            state.snapshot.cause is ConnectionCause.UserDisconnected
        ) {
            return state to emptyList()
        }
        val outboundId = state.snapshot.activeOutboundId
        val failures = state.consecutiveFailures + 1
        return when {
            !cause.isRetryable() -> failNow(state, cause)
            outboundId == null -> failNow(state, cause)
            failures < context.reconnect.maxAttempts -> retrySame(state, outboundId, failures, cause, context)
            else -> afterReconnectBudget(state, context, outboundId, failures, cause)
        }
    }

    private fun retrySame(
        state: MachineState,
        outboundId: String,
        failures: Int,
        cause: ConnectionCause,
        context: MachineContext,
    ): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.RECONNECTING,
            cause = cause,
            attempt = failures,
            uplinkBps = 0,
            downlinkBps = 0,
            uplinkTotal = 0,
            downlinkTotal = 0,
        )
        val delay = context.reconnect.backoffMs(failures)
        return state.copy(snapshot = snapshot, consecutiveFailures = failures) to listOf(
            PolicyCommand.ScheduleRetry(outboundId, delay, failures),
        )
    }

    private fun afterReconnectBudget(
        state: MachineState,
        context: MachineContext,
        outboundId: String,
        failures: Int,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        val dead = state.knownDead + outboundId
        val eligible = GroupFailoverPolicy.isEligible(context.failover, context.group, outboundId)
        val candidates = context.group?.let { GroupFailoverPolicy.candidates(it, outboundId, dead) }.orEmpty()
        if (eligible && candidates.isNotEmpty()) {
            val snapshot = state.snapshot.copy(
                state = ConnectionState.RECONNECTING,
                cause = ConnectionCause.ReconnectExhausted(failures, cause),
                attempt = failures,
            )
            return state.copy(snapshot = snapshot, consecutiveFailures = failures, knownDead = dead) to listOf(
                PolicyCommand.ProbeGroup(candidates),
            )
        }
        // Exhausting a retry burst must not leave an otherwise valid VPN off forever.
        // Continue with capped backoff and allow a fresh group probe next burst.
        return retrySame(
            state.copy(knownDead = emptySet()),
            outboundId,
            failures.coerceAtMost(context.reconnect.maxAttempts),
            ConnectionCause.ReconnectExhausted(failures, cause),
            context,
        )
    }

    private fun onProbe(
        state: MachineState,
        context: MachineContext,
        alive: Set<String>,
    ): Pair<MachineState, List<PolicyCommand>> {
        val current = state.snapshot.activeOutboundId
        val group = context.group
        if (group == null) {
            return current?.let {
                retrySame(state, it, state.consecutiveFailures, ConnectionCause.FailoverExhausted("?"), context)
            } ?: failNow(state, ConnectionCause.FailoverExhausted("?"))
        }
        val winner = GroupFailoverPolicy.firstAlive(
            GroupFailoverPolicy.candidates(group, current, state.knownDead),
            alive,
        )
        if (winner == null) {
            return current?.let {
                retrySame(
                    state.copy(knownDead = emptySet()),
                    it,
                    state.consecutiveFailures,
                    ConnectionCause.FailoverExhausted(group.name),
                    context,
                )
            } ?: failNow(state, ConnectionCause.FailoverExhausted(group.name))
        }
        val from = current ?: winner
        val snapshot = state.snapshot.copy(
            state = ConnectionState.CONNECTING,
            cause = null,
            activeOutboundId = winner,
            attempt = 0,
            banner = FailoverBanner(
                fromName = group.labels[from] ?: from,
                toName = group.labels[winner] ?: winner,
                groupName = group.name,
            ),
        )
        return state.copy(snapshot = snapshot, consecutiveFailures = 0) to listOf(
            PolicyCommand.ShowBanner(from, winner, group.name),
            PolicyCommand.StartEngine(winner),
        )
    }
}
