package app.lernet.engine.policy

import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.FailoverBanner
import app.lernet.engine.RunMode
import kotlin.random.Random

data class MachineContext(
    val reconnect: ReconnectSettings,
    val failover: FailoverSettings,
    val group: ManualFailoverGroup?,
    /** Draws the random recheck pause for configs that stayed unavailable ([RecheckJitter]). */
    val recheckMs: () -> Long = { RecheckJitter.nextMs() },
    /** Time since the underlying network last appeared or changed ([LinkJudge.SETTLE_MS]). */
    val msSinceNetworkChange: () -> Long = { Long.MAX_VALUE },
    /** Wall clock; virtual in tests. */
    val nowMs: () -> Long = { System.currentTimeMillis() },
    /** Configs that died and rest ([ConfigPenalties]); owned and persisted by the controller. */
    val penalties: ConfigPenalties = ConfigPenalties(),
    /** How long the handshake budget of this config's server still forbids a new connection ([HandshakeBudget]). */
    val budgetWaitMs: (outboundId: String) -> Long = { 0L },
    val random: Random = Random.Default,
)

data class MachineState(
    val snapshot: ConnectionSnapshot,
    val consecutiveFailures: Int,
    /**
     * The VPN has been up in this session (survives a failover restart to another config).
     * While true, no core failure except a truly unrecoverable one may drop the VPN.
     */
    val sessionHeld: Boolean = false,
    /** Last [PolicyEvent.EngineStatus.proxiedReplies] value seen. */
    val proxiedReplies: Long = 0,
    /** Rounds in a row in which neither the active config nor any folder config answered. */
    val silentRounds: Int = 0,
    /** A round of folder checks is running; a second one is not started meanwhile. */
    val checking: Boolean = false,
    /** Wall clock of the last core (re)start this machine ordered. */
    val lastRestartAtMs: Long = 0L,
) {
    companion object {
        fun idle(mode: RunMode = RunMode.FULL_VPN): MachineState =
            MachineState(ConnectionSnapshot.idle(mode), consecutiveFailures = 0)
    }
}

/**
 * Connection rules (the same on Windows, see the desktop controller):
 *
 * - Silence, missed DNS and the block detector never restart the core: they only ask the tunnel check
 *   for a look and show «связь глушат» ([LinkHold.JAMMED]). Live connections and the xhttp pool stay.
 * - Two failed tunnel checks mean the config is dead. Folder configs that do not rest are checked by a
 *   real request through each of them; only one that answered replaces the running config. Without one
 *   the connection is held and looked at again after a growing pause.
 * - A config that failed rests 2 → 10 → 30 min ([ConfigPenalties]); the list survives restarts, so a
 *   folder cannot swing A → B → A.
 * - The core itself failing is started again after [RetryBackoff], never sooner than the handshake budget.
 * - Rounds in which no server answered while the network is up switch to [LinkHold.ALL_SILENT] and to
 *   rare checks ([SilentNetworkBackoff]): operator white lists or a shutdown.
 */
class ConnectionPolicyMachine {
    fun reduce(
        state: MachineState,
        context: MachineContext,
        event: PolicyEvent,
    ): Pair<MachineState, List<PolicyCommand>> = when (event) {
        is PolicyEvent.StartRequested -> start(state, context, event)
        PolicyEvent.EngineStarted -> accepted(state)
        is PolicyEvent.EngineStatus -> onStatus(state, context, event)
        PolicyEvent.TunnelVerified -> verified(state, context)
        is PolicyEvent.EngineFailed -> when (event.cause) {
            is ConnectionCause.TunnelHealthFailed -> onTunnelDead(state, context)
            is ConnectionCause.BlockingSuspected -> onHint(state)
            else -> onFailure(state, context, event.cause)
        }
        PolicyEvent.WatchdogMiss -> onWatchdog(state, context)
        PolicyEvent.DnsHealthTimeout -> onHint(state)
        PolicyEvent.DnsPathOk -> onDnsPathOk(state)
        PolicyEvent.ConnectTimeout -> onConnectTimeout(state, context)
        PolicyEvent.OutboundReady -> onOutboundReady(state)
        PolicyEvent.UserDisconnect -> userDisconnect(state)
        is PolicyEvent.ProbeCompleted -> onProbe(state, context, event)
        PolicyEvent.PermissionDenied ->
            failNow(state, ConnectionCause.VpnPermissionDenied)
    }

    private fun start(
        state: MachineState,
        context: MachineContext,
        event: PolicyEvent.StartRequested,
    ): Pair<MachineState, List<PolicyCommand>> {
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
            hold = null,
            nextCheckAtMs = null,
        )
        // A restart while the VPN is up (failover to the next config, manual switch) keeps the
        // session's protection: a failing core there is retried with the interface held closed.
        val held = !isTerminal(state) &&
            (state.sessionHeld || state.snapshot.state == ConnectionState.CONNECTED || state.snapshot.state == ConnectionState.RECONNECTING)
        // Resting configs are not forgotten here: that reset was what let a folder swing A → B → A.
        return MachineState(snapshot, consecutiveFailures = 0, sessionHeld = held, lastRestartAtMs = context.nowMs()) to
            listOf(PolicyCommand.StartEngine(event.outboundId, event.reason))
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
        context: MachineContext,
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
        // A real reply through the VPN exit starts a fresh health window. Bytes of direct
        // connections (rules that bypass the tunnel) prove nothing about the config, so with the
        // connection feed only proxied replies count. Without the feed: legacy total-downlink rule.
        val proxied = event.proxiedReplies
        val recovered = if (proxied != null) {
            proxied > state.proxiedReplies
        } else {
            event.downlinkTotal > state.snapshot.downlinkTotal
        }
        val next = state.copy(proxiedReplies = proxied ?: state.proxiedReplies, snapshot = snapshot)
        if (!recovered) return next to emptyList()
        return if (state.snapshot.state == ConnectionState.CONNECTED) worked(next, context) else
            next.copy(consecutiveFailures = 0) to emptyList()
    }

    private fun verified(state: MachineState, context: MachineContext): Pair<MachineState, List<PolicyCommand>> =
        if (isTerminal(state)) state to emptyList() else worked(state, context)

    /** Replies came back through the config: it works, whatever was suspected before. */
    private fun worked(state: MachineState, context: MachineContext): Pair<MachineState, List<PolicyCommand>> {
        state.snapshot.activeOutboundId?.let(context.penalties::clear)
        val snapshot = if (state.snapshot.state == ConnectionState.CONNECTED) {
            state.snapshot.copy(hold = null, nextCheckAtMs = null)
        } else {
            state.snapshot
        }
        return state.copy(snapshot = snapshot, consecutiveFailures = 0, silentRounds = 0) to emptyList()
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
        val connectSeconds = (context.reconnect.connectTimeoutMs / 1000L).toInt().coerceAtLeast(1)
        return when (state.snapshot.state) {
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING,
            -> onFailure(state, context, ConnectionCause.ConnectTimeout(connectSeconds))
            ConnectionState.CONNECTED -> onHint(state)
            ConnectionState.DISCONNECTED,
            ConnectionState.FAILED,
            -> state to emptyList()
        }
    }

    /**
     * Missed DNS, a silent watchdog window or the block detector: proof of nothing. The connection
     * is held as it is and the tunnel check is asked for one look (it keeps its own cooldown).
     */
    private fun onHint(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        if (state.snapshot.state != ConnectionState.CONNECTED) {
            return state to emptyList()
        }
        val hold = state.snapshot.hold ?: LinkHold.JAMMED
        return state.copy(snapshot = state.snapshot.copy(hold = hold)) to listOf(PolicyCommand.RequestCheck)
    }

    private fun onDnsPathOk(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        if (isTerminal(state)) {
            return state to emptyList()
        }
        return state.copy(snapshot = state.snapshot.copy(dnsOk = true)) to emptyList()
    }

    private fun connected(state: MachineState): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.CONNECTED,
            cause = null,
            attempt = 0,
        )
        return state.copy(snapshot = snapshot, sessionHeld = true) to emptyList()
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
            hold = null,
            nextCheckAtMs = null,
        )
        return MachineState(snapshot, 0) to listOf(PolicyCommand.StopEngine(cause))
    }

    private fun failNow(
        state: MachineState,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.FAILED,
            cause = cause,
            compiledJson = null,
            hold = null,
            nextCheckAtMs = null,
        )
        return state.copy(snapshot = snapshot, checking = false) to listOf(PolicyCommand.StopEngine(cause))
    }

    /**
     * Two tunnel checks in a row failed while the core runs. The core and its live connections stay:
     * folder configs that do not rest are checked for real, and only one that answered replaces this one.
     */
    private fun onTunnelDead(state: MachineState, context: MachineContext): Pair<MachineState, List<PolicyCommand>> {
        if (state.snapshot.state != ConnectionState.CONNECTED || state.checking) {
            return state to emptyList()
        }
        val current = state.snapshot.activeOutboundId ?: return state to emptyList()
        val now = context.nowMs()
        val candidates = freeCandidates(context, current, now)
        val decision = LinkJudge.decide(
            networkUp = true,
            failure = LinkFailure.TUNNEL_DEAD,
            failuresOnConfig = state.consecutiveFailures + 1,
            msSinceNetworkChange = context.msSinceNetworkChange(),
            hasAlternative = candidates.isNotEmpty(),
        )
        if (decision == LinkDecision.HOLD && context.msSinceNetworkChange() in 0 until LinkJudge.SETTLE_MS) {
            // The network appeared a moment ago: nothing is blamed on the config yet.
            val held = state.snapshot.copy(hold = state.snapshot.hold ?: LinkHold.JAMMED, nextCheckAtMs = now + SETTLE_RECHECK_MS)
            return state.copy(snapshot = held) to listOf(PolicyCommand.HoldCheck(SETTLE_RECHECK_MS))
        }
        context.penalties.fail(current, now)
        if (decision == LinkDecision.SWITCH_NOW) {
            val held = state.snapshot.copy(hold = state.snapshot.hold ?: LinkHold.JAMMED, nextCheckAtMs = null)
            return state.copy(snapshot = held, checking = true) to listOf(PolicyCommand.ProbeGroup(candidates))
        }
        return silentRound(state, context, now)
    }

    /** Nothing answered this round while the core runs: hold, count the round, look again later. */
    private fun silentRound(state: MachineState, context: MachineContext, now: Long): Pair<MachineState, List<PolicyCommand>> {
        val rounds = state.silentRounds + 1
        val hold = if (rounds >= ALL_SILENT_ROUNDS) LinkHold.ALL_SILENT else LinkHold.JAMMED
        val current = state.snapshot.activeOutboundId
        // A core that kept a broken pool is started once more, at most every STALE_CORE_GAP_MS.
        if (rounds == STALE_CORE_ROUND && current != null && now - state.lastRestartAtMs >= STALE_CORE_GAP_MS) {
            val delay = context.budgetWaitMs(current).coerceAtLeast(0L)
            val (next, commands) = retrySame(
                state.copy(silentRounds = rounds, checking = false),
                context,
                current,
                state.consecutiveFailures.coerceAtLeast(1),
                ConnectionCause.TunnelHealthFailed("no reply in $rounds rounds"),
                delay,
                PolicyCommand.REASON_STALE_CORE,
            )
            return next.copy(snapshot = next.snapshot.copy(hold = hold)) to commands
        }
        val delay = SilentNetworkBackoff.delayMs(rounds, context.random)
        val snapshot = state.snapshot.copy(hold = hold, nextCheckAtMs = now + delay)
        return state.copy(snapshot = snapshot, silentRounds = rounds, checking = false) to listOf(PolicyCommand.HoldCheck(delay))
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
        val reason = if (cause is ConnectionCause.ConnectTimeout) PolicyCommand.REASON_CONNECT_TIMEOUT else PolicyCommand.REASON_CORE_FAILED
        return when {
            !keepsSession(cause) -> failNow(state, cause)
            outboundId == null -> failNow(state, cause)
            // A core that never came up for a fresh connect gets one retry series, then a clear
            // final error: the user is watching and nothing was protected yet.
            cause is ConnectionCause.EngineStartFailed && !state.sessionHeld &&
                failures >= context.reconnect.maxAttempts ->
                failNow(state, ConnectionCause.ReconnectExhausted(failures, cause))
            // Already in a series of rounds without any answer: rare looks, no burst.
            state.silentRounds >= STALE_CORE_ROUND -> lookElsewhere(state, context, outboundId, failures, cause)
            else -> {
                val now = context.nowMs()
                val decision = LinkJudge.decide(
                    networkUp = true,
                    failure = LinkFailure.ENGINE_ERROR,
                    failuresOnConfig = failures,
                    msSinceNetworkChange = context.msSinceNetworkChange(),
                    hasAlternative = freeCandidates(context, outboundId, now).isNotEmpty(),
                )
                if (decision == LinkDecision.RETRY_SAME && failures < context.reconnect.maxAttempts) {
                    val budgetWait = context.budgetWaitMs(outboundId)
                    val delay = RetryBackoff.delayMs(failures, context.reconnect.backoffCapMs, budgetWait, context.random)
                    retrySame(state, context, outboundId, failures, cause, delay, reason)
                } else {
                    lookElsewhere(state, context, outboundId, failures, cause)
                }
            }
        }
    }

    /** Folder configs that may be tried now: not this one and not resting. */
    private fun freeCandidates(context: MachineContext, current: String, now: Long): List<String> {
        val group = context.group ?: return emptyList()
        if (!GroupFailoverPolicy.isEligible(context.failover, group, current)) return emptyList()
        return GroupFailoverPolicy.candidates(group, current, context.penalties.resting(now))
    }

    private fun keepsSession(cause: ConnectionCause): Boolean =
        cause.isRetryable() || cause is ConnectionCause.EngineStartFailed

    private fun retrySame(
        state: MachineState,
        context: MachineContext,
        outboundId: String,
        failures: Int,
        cause: ConnectionCause,
        delayMs: Long,
        reason: String,
    ): Pair<MachineState, List<PolicyCommand>> {
        val snapshot = state.snapshot.copy(
            state = ConnectionState.RECONNECTING,
            cause = cause,
            attempt = failures,
            uplinkBps = 0,
            downlinkBps = 0,
            uplinkTotal = 0,
            downlinkTotal = 0,
            nextCheckAtMs = context.nowMs() + delayMs,
        )
        return state.copy(
            snapshot = snapshot,
            consecutiveFailures = failures,
            checking = false,
            lastRestartAtMs = context.nowMs() + delayMs,
        ) to listOf(PolicyCommand.ScheduleRetry(outboundId, delayMs, failures, reason))
    }

    /** The config died: it rests, folder configs that do not rest get a real check, else a long pause. */
    private fun lookElsewhere(
        state: MachineState,
        context: MachineContext,
        outboundId: String,
        failures: Int,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        val now = context.nowMs()
        context.penalties.fail(outboundId, now)
        val candidates = freeCandidates(context, outboundId, now)
        if (candidates.isNotEmpty()) {
            val snapshot = state.snapshot.copy(
                state = ConnectionState.RECONNECTING,
                cause = ConnectionCause.ReconnectExhausted(failures, cause),
                attempt = failures,
                nextCheckAtMs = null,
            )
            return state.copy(snapshot = snapshot, consecutiveFailures = failures, checking = true) to
                listOf(PolicyCommand.ProbeGroup(candidates))
        }
        return recheckLater(state, context, outboundId, failures, cause)
    }

    /**
     * Every config is unavailable now. A retry series must not leave an otherwise valid VPN off
     * forever: the same config is started again after a random pause, longer with every silent round.
     */
    private fun recheckLater(
        state: MachineState,
        context: MachineContext,
        outboundId: String,
        failures: Int,
        cause: ConnectionCause,
    ): Pair<MachineState, List<PolicyCommand>> {
        val rounds = state.silentRounds + 1
        val pause = if (rounds >= ALL_SILENT_ROUNDS) SilentNetworkBackoff.delayMs(rounds, context.random) else context.recheckMs()
        val delay = maxOf(pause, context.budgetWaitMs(outboundId))
        val (next, commands) = retrySame(
            state.copy(silentRounds = rounds),
            context,
            outboundId,
            failures.coerceAtMost(context.reconnect.maxAttempts),
            cause as? ConnectionCause.FailoverExhausted ?: ConnectionCause.ReconnectExhausted(failures, cause),
            delay,
            PolicyCommand.REASON_RECHECK,
        )
        val hold = if (rounds >= ALL_SILENT_ROUNDS) LinkHold.ALL_SILENT else next.snapshot.hold
        return next.copy(snapshot = next.snapshot.copy(hold = hold)) to commands
    }

    private fun onProbe(
        state: MachineState,
        context: MachineContext,
        event: PolicyEvent.ProbeCompleted,
    ): Pair<MachineState, List<PolicyCommand>> {
        if (isTerminal(state) || !state.checking) {
            return state to emptyList()
        }
        val current = state.snapshot.activeOutboundId ?: return failNow(state, ConnectionCause.FailoverExhausted("?"))
        val group = context.group
        val now = context.nowMs()
        val order = group?.let { GroupFailoverPolicy.candidates(it, current, context.penalties.resting(now)) }.orEmpty()
        val winner = GroupFailoverPolicy.firstAlive(order, event.aliveOutboundIds)
        // Every candidate that was really checked and did not answer rests, winner or not.
        event.checkedOutboundIds.filter { it !in event.aliveOutboundIds && it != current }
            .forEach { context.penalties.fail(it, now) }
        if (winner == null || group == null) {
            if (event.checkedOutboundIds.isEmpty()) {
                order.filter { it !in event.aliveOutboundIds }.forEach { context.penalties.fail(it, now) }
            }
            val idle = state.copy(checking = false)
            return if (state.snapshot.state == ConnectionState.CONNECTED) {
                silentRound(idle, context, now)
            } else {
                recheckLater(
                    idle,
                    context,
                    current,
                    state.consecutiveFailures,
                    ConnectionCause.FailoverExhausted(group?.name ?: "?"),
                )
            }
        }
        val snapshot = state.snapshot.copy(
            state = ConnectionState.CONNECTING,
            cause = null,
            activeOutboundId = winner,
            attempt = 0,
            hold = null,
            nextCheckAtMs = null,
            banner = FailoverBanner(
                fromName = group.labels[current] ?: current,
                toName = group.labels[winner] ?: winner,
                groupName = group.name,
            ),
        )
        return state.copy(
            snapshot = snapshot,
            consecutiveFailures = 0,
            silentRounds = 0,
            checking = false,
            lastRestartAtMs = now,
        ) to listOf(
            PolicyCommand.ShowBanner(current, winner, group.name),
            PolicyCommand.StartEngine(winner, PolicyCommand.REASON_SWITCH),
        )
    }

    private companion object {
        const val ALL_SILENT_ROUNDS = LinkJudge.ALL_SILENT_ROUNDS
        const val STALE_CORE_ROUND = LinkJudge.STALE_CORE_ROUND
        const val STALE_CORE_GAP_MS = LinkJudge.STALE_CORE_GAP_MS
        const val SETTLE_RECHECK_MS = LinkJudge.SETTLE_RECHECK_MS
    }
}
