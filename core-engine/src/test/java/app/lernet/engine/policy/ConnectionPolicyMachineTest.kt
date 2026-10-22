package app.lernet.engine.policy

import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class ConnectionPolicyMachineTest {
    private val machine = ConnectionPolicyMachine()
    private val reconnect = ReconnectSettings(maxAttempts = 3, initialBackoffMs = 1_000, backoffCapMs = 8_000)

    @Test
    fun startThenConnected() {
        var state = MachineState.idle()
        val started = machine.reduce(state, ctx(), startEvent()).also { state = it.first }
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        assertThat(started.second).containsExactly(PolicyCommand.StartEngine("out-a"))
        state = machine.reduce(state, ctx(), PolicyEvent.EngineStarted).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        state = machine.reduce(state, ctx(), PolicyEvent.OutboundReady).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
        state = machine.reduce(state, ctx(), healthyStatus()).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(state.snapshot.activeOutboundId).isEqualTo("out-a")
    }

    @Test
    fun engineStartedDoesNotClaimConnectedUntilProbe() {
        var state = MachineState.idle()
        state = machine.reduce(state, ctx(), startEvent()).first
        state = machine.reduce(state, ctx(), PolicyEvent.EngineStarted).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        state = machine.reduce(state, ctx(), zeroStatus()).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        state = machine.reduce(state, ctx(), PolicyEvent.OutboundReady).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun zeroStatusWhileConnectingDoesNotClaimConnected() {
        var state = MachineState.idle()
        state = machine.reduce(state, ctx(), startEvent()).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        state = machine.reduce(state, ctx(), zeroStatus()).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
    }

    @Test
    fun outboundUnreachableRetries() {
        var state = MachineState.idle()
        state = machine.reduce(state, ctx(), startEvent()).first
        state = machine.reduce(state, ctx(), PolicyEvent.EngineStarted).first
        val (next, commands) = machine.reduce(
            state,
            ctx(),
            PolicyEvent.EngineFailed(ConnectionCause.OutboundUnreachable("151.1.2.3:443 protect=ok refused")),
        )
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(next.snapshot.cause).isInstanceOf(ConnectionCause.OutboundUnreachable::class.java)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
    }

    @Test
    fun watchdogMissWhileConnectingRetries() {
        var state = MachineState.idle()
        state = machine.reduce(state, ctx(), startEvent()).first
        val (next, commands) = machine.reduce(state, ctx(), PolicyEvent.WatchdogMiss)
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(next.snapshot.cause).isInstanceOf(ConnectionCause.ConnectTimeout::class.java)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
    }

    @Test
    fun connectTimeoutWhileConnectingRetries() {
        var state = MachineState.idle()
        state = machine.reduce(state, ctx(), startEvent()).first
        val (next, commands) = machine.reduce(state, ctx(), PolicyEvent.ConnectTimeout)
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(next.snapshot.cause).isInstanceOf(ConnectionCause.ConnectTimeout::class.java)
        assertThat((next.snapshot.cause as ConnectionCause.ConnectTimeout).timeoutSeconds).isEqualTo(45)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
    }

    @Test
    fun userDisconnectFromConnectedReachesDisconnectedAndIgnoresLateStart() {
        var state = connected()
        val (stopped, commands) = machine.reduce(state, ctx(), PolicyEvent.UserDisconnect)
        state = stopped
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
        val (late, lateCommands) = machine.reduce(state, ctx(), PolicyEvent.EngineStarted)
        assertThat(late.snapshot.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(lateCommands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
        val afterTraffic = machine.reduce(late, ctx(), healthyStatus()).first
        assertThat(afterTraffic.snapshot.state).isEqualTo(ConnectionState.DISCONNECTED)
    }

    @Test
    fun exhaustedBurstKeepsRetryingSameOutboundWhenFailoverOff() {
        var state = connected()
        val ctx = ctx(failover = FailoverSettings(enabled = false), group = sampleGroup())
        repeat(3) {
            state = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.DialTimeout(5))).first
        }
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(state.snapshot.cause).isInstanceOf(ConnectionCause.ReconnectExhausted::class.java)
        assertThat(state.snapshot.activeOutboundId).isEqualTo("out-a")
    }

    @Test
    fun unavailableConfigIsRecheckedAfterARandomPauseUpTo5Minutes() {
        val ctx = ctx(failover = FailoverSettings(enabled = false), group = sampleGroup())
        val pauses = List(40) {
            var state = connected()
            var last: List<PolicyCommand> = emptyList()
            repeat(3) {
                val step = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.DialTimeout(5)))
                state = step.first
                last = step.second
            }
            assertThat(state.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
            last.filterIsInstance<PolicyCommand.ScheduleRetry>().single().delayMs
        }
        pauses.forEach { pause ->
            assertThat(pause).isAtLeast(45_000L)
            assertThat(pause).isAtMost(300_000L)
            assertThat(pause % 1_000).isEqualTo(0L)
        }
        assertThat(pauses.toSet().size).isGreaterThan(20)
    }

    @Test
    fun directSiteTrafficDoesNotResetFailuresButTunnelRepliesDo() {
        val ctx = ctx(failover = FailoverSettings(enabled = false), group = sampleGroup())
        var state = connected()
        state = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.DialTimeout(5))).first
        state = machine.reduce(state, ctx, PolicyEvent.OutboundReady).first
        assertThat(state.consecutiveFailures).isEqualTo(1)
        // Direct sites keep answering: total downlink grows, proxied replies do not.
        state = machine.reduce(state, ctx, status(downlinkTotal = 90_000, proxiedReplies = 0)).first
        assertThat(state.consecutiveFailures).isEqualTo(1)
        // A reply through the VPN exit is real evidence.
        state = machine.reduce(state, ctx, status(downlinkTotal = 91_000, proxiedReplies = 3)).first
        assertThat(state.consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun failuresReachTheFolderProbeDespiteDirectTraffic() {
        val ctx = ctx(failover = FailoverSettings(enabled = true, groupId = "g1"), group = sampleGroup())
        var state = connected()
        var commands: List<PolicyCommand> = emptyList()
        var down = 0L
        repeat(3) {
            down += 50_000
            state = machine.reduce(state, ctx, status(downlinkTotal = down, proxiedReplies = 0)).first
            val step = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.DialTimeout(5)))
            state = step.first
            commands = step.second
        }
        assertThat(commands.filterIsInstance<PolicyCommand.ProbeGroup>()).isNotEmpty()
    }

    @Test
    fun verifiedTunnelResetsFailures() {
        var state = connected()
        state = machine.reduce(state, ctx(), PolicyEvent.EngineFailed(ConnectionCause.DialTimeout(5))).first
        assertThat(state.consecutiveFailures).isEqualTo(1)
        state = machine.reduce(state, ctx(), PolicyEvent.TunnelVerified).first
        assertThat(state.consecutiveFailures).isEqualTo(0)
    }

    @Test
    fun configDecodeGoesFailedNotReconnecting() {
        var state = connected()
        val (next, commands) = machine.reduce(
            state,
            ctx(),
            PolicyEvent.EngineFailed(
                ConnectionCause.InvalidConfig(listOf("unknown transport type: xhttp")),
            ),
        )
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.FAILED)
        assertThat(next.snapshot.cause).isInstanceOf(ConnectionCause.InvalidConfig::class.java)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
    }

    @Test
    fun firstStartFailureRetriesOneBurstThenFailsClearly() {
        var state = machine.reduce(MachineState.idle(), ctx(), startEvent()).first
        // maxAttempts = 3: two paused retries, the third failure ends the fresh attempt.
        repeat(2) {
            val (next, commands) = machine.reduce(
                state,
                ctx(),
                PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed("setup exploded")),
            )
            assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
            assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>().single().delayMs).isGreaterThan(0)
            assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
            state = next
        }
        val (last, commands) = machine.reduce(
            state,
            ctx(),
            PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed("setup exploded")),
        )
        assertThat(last.snapshot.state).isEqualTo(ConnectionState.FAILED)
        assertThat(last.snapshot.cause).isInstanceOf(ConnectionCause.ReconnectExhausted::class.java)
        assertThat(last.snapshot.cause?.labelRu()).contains("VPN не запустился")
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
    }

    @Test
    fun coreFailureOnFailoverRestartKeepsTheVpn() {
        // Session was up, then a failover restarted the core on another config (state CONNECTING again).
        var state = connected()
        state = machine.reduce(state, ctx(), startEvent().copy(outboundId = "out-b")).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        assertThat(state.sessionHeld).isTrue()
        repeat(6) {
            val (next, commands) = machine.reduce(
                state,
                ctx(),
                PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed("failover core exploded")),
            )
            assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
            assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
            assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
            state = next
        }
    }

    @Test
    fun freshConnectAfterStopDoesNotInheritTheHold() {
        var state = connected()
        state = machine.reduce(state, ctx(), PolicyEvent.UserDisconnect).first
        state = machine.reduce(state, ctx(), startEvent()).first
        assertThat(state.sessionHeld).isFalse()
    }

    @Test
    fun unrecoverableCausesStillEndAHeldSession() {
        listOf(
            ConnectionCause.VpnPermissionDenied,
            ConnectionCause.ServiceRevoked,
            ConnectionCause.InvalidConfig(listOf("x")),
            ConnectionCause.EngineUnavailable,
        ).forEach { cause ->
            val (next, commands) = machine.reduce(connected(), ctx(), PolicyEvent.EngineFailed(cause))
            assertThat(next.snapshot.state).isEqualTo(ConnectionState.FAILED)
            assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
        }
    }

    @Test
    fun coreFailureAfterConnectKeepsSessionAndRetries() {
        val (next, commands) = machine.reduce(
            connected(),
            ctx(),
            PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed("setup exploded")),
        )
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
    }

    @Test
    fun failedCoreRestartKeepsRetryingWithoutStoppingTheTunnel() {
        var state = connected()
        repeat(6) {
            val (next, commands) = machine.reduce(
                state,
                ctx(),
                PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed("restart exploded")),
            )
            assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
            assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
            assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
            state = next
        }
    }

    @Test
    fun revokeAndPermissionLossStillEndTheSession() {
        listOf(ConnectionCause.ServiceRevoked, ConnectionCause.VpnPermissionDenied).forEach { cause ->
            val (next, commands) = machine.reduce(connected(), ctx(), PolicyEvent.EngineFailed(cause))
            assertThat(next.snapshot.state).isEqualTo(ConnectionState.FAILED)
            assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
        }
    }

    @Test
    fun invalidStartFromIdleGoesFailed() {
        val (next, commands) = machine.reduce(
            MachineState.idle(),
            ctx(),
            PolicyEvent.EngineFailed(ConnectionCause.InvalidRouteTree(listOf("Дерево маршрутов пусто"))),
        )
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.FAILED)
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
    }

    @Test
    fun userDisconnectEndsDisconnected() {
        var state = connected()
        val (next, commands) = machine.reduce(state, ctx(), PolicyEvent.UserDisconnect)
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
    }

    @Test
    fun silenceDnsAndBlockDetectorHoldTheConnectionWithoutRestart() {
        listOf(
            PolicyEvent.WatchdogMiss,
            PolicyEvent.DnsHealthTimeout,
            PolicyEvent.EngineFailed(ConnectionCause.BlockingSuspected(BlockingSign.RESET_AFTER_HELLO)),
        ).forEach { event ->
            val (next, commands) = machine.reduce(connected(), folder(), event)
            assertThat(next.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
            assertThat(next.snapshot.hold).isEqualTo(LinkHold.JAMMED)
            assertThat(next.snapshot.cause).isNull()
            assertThat(commands).containsExactly(PolicyCommand.RequestCheck)
        }
    }

    @Test
    fun repliesThroughTheTunnelClearTheHold() {
        var state = machine.reduce(connected(), ctx(), PolicyEvent.WatchdogMiss).first
        state = machine.reduce(state, ctx(), status(downlinkTotal = 1_000, proxiedReplies = 0)).first
        assertThat(state.snapshot.hold).isEqualTo(LinkHold.JAMMED)
        state = machine.reduce(state, ctx(), status(downlinkTotal = 2_000, proxiedReplies = 4)).first
        assertThat(state.snapshot.hold).isNull()
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
    }

    @Test
    fun coreFailureRetriesWithTheNewBackoffNotOneSecond() {
        var state = connected()
        val first = machine.reduce(state, ctx(), PolicyEvent.EngineFailed(ConnectionCause.TlsFailure("hang")))
        state = first.first
        val retry = first.second.filterIsInstance<PolicyCommand.ScheduleRetry>().single()
        assertThat(retry.outboundId).isEqualTo("out-a")
        assertThat(retry.reason).isEqualTo(PolicyCommand.REASON_CORE_FAILED)
        assertThat(retry.delayMs).isIn(com.google.common.collect.Range.closed(5_000L, 6_250L))
        val second = machine.reduce(state, ctx(), PolicyEvent.EngineFailed(ConnectionCause.DialFailure("rst")))
        // 15 s step, capped by the user's 8 s ceiling.
        assertThat(second.second.filterIsInstance<PolicyCommand.ScheduleRetry>().single().delayMs).isEqualTo(8_000L)
    }

    @Test
    fun retryNeverComesSoonerThanTheHandshakeBudget() {
        val (_, commands) = machine.reduce(
            connected(), ctx(budgetWaitMs = 41_000L), PolicyEvent.EngineFailed(ConnectionCause.DialFailure("rst")),
        )
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>().single().delayMs).isEqualTo(41_000L)
    }

    @Test
    fun connectTimeoutRetryCarriesItsReason() {
        val state = machine.reduce(MachineState.idle(), ctx(), startEvent()).first
        val (_, commands) = machine.reduce(state, ctx(), PolicyEvent.ConnectTimeout)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>().single().reason)
            .isEqualTo(PolicyCommand.REASON_CONNECT_TIMEOUT)
    }

    @Test
    fun deadTunnelKeepsTheCoreAndChecksTheFolderFirst() {
        val penalties = ConfigPenalties(random = Random(1))
        val context = ctx(FailoverSettings(true, "g1"), sampleGroup(), penalties)
        val (next, commands) = machine.reduce(connected(), context, tunnelDead())
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(next.checking).isTrue()
        assertThat(commands).containsExactly(PolicyCommand.ProbeGroup(listOf("out-b")))
        assertThat(penalties.isResting("out-a", 0L)).isTrue()
    }

    @Test
    fun onlyACheckedReplacementSwitches() {
        val penalties = ConfigPenalties(random = Random(1))
        val context = ctx(FailoverSettings(true, "g1"), sampleGroup(), penalties)
        val checking = machine.reduce(connected(), context, tunnelDead()).first
        val (next, commands) = machine.reduce(checking, context, PolicyEvent.ProbeCompleted(setOf("out-b"), setOf("out-b")))
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        assertThat(next.snapshot.activeOutboundId).isEqualTo("out-b")
        assertThat(commands).contains(PolicyCommand.StartEngine("out-b", PolicyCommand.REASON_SWITCH))
    }

    @Test
    fun noAnsweringReplacementHoldsAndRestsTheCheckedOnes() {
        val penalties = ConfigPenalties(random = Random(1))
        val context = ctx(FailoverSettings(true, "g1"), sampleGroup(), penalties)
        val checking = machine.reduce(connected(), context, tunnelDead()).first
        val (next, commands) = machine.reduce(checking, context, PolicyEvent.ProbeCompleted(emptySet(), setOf("out-b")))
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(next.snapshot.hold).isEqualTo(LinkHold.JAMMED)
        assertThat(next.silentRounds).isEqualTo(1)
        assertThat(penalties.isResting("out-b", 0L)).isTrue()
        val hold = commands.filterIsInstance<PolicyCommand.HoldCheck>().single()
        assertThat(hold.delayMs).isIn(com.google.common.collect.Range.closed(48_000L, 72_000L))
        assertThat(commands.filterIsInstance<PolicyCommand.StartEngine>()).isEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isEmpty()
    }

    @Test
    fun aRestingConfigIsNotPickedAgainSoNoPingPong() {
        var now = 0L
        val penalties = ConfigPenalties(random = Random(1))
        val context = ctx(FailoverSettings(true, "g1"), sampleGroup(), penalties, nowMs = { now })
        // A dies, B answers: switch to B.
        var state = machine.reduce(connected(), context, tunnelDead()).first
        state = machine.reduce(state, context, PolicyEvent.ProbeCompleted(setOf("out-b"), setOf("out-b"))).first
        state = machine.reduce(state, context, startEvent().copy(outboundId = "out-b")).first
        state = machine.reduce(state, context, PolicyEvent.OutboundReady).first
        // A start does not forget that A rests.
        assertThat(penalties.isResting("out-a", now)).isTrue()
        now += 30_000
        // B dies a moment later: A still rests, so nothing switches back.
        val (next, commands) = machine.reduce(state, context, tunnelDead())
        assertThat(commands.filterIsInstance<PolicyCommand.ProbeGroup>()).isEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.StartEngine>()).isEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.HoldCheck>()).hasSize(1)
        assertThat(next.snapshot.activeOutboundId).isEqualTo("out-b")
    }

    @Test
    fun silentRoundsGoToRareChecksAndSayWhiteLists() {
        var now = 0L
        val context = ctx(nowMs = { now })
        var state = connected()
        val delays = mutableListOf<Long>()
        repeat(4) {
            now += 60_000
            val (next, commands) = machine.reduce(state, context, tunnelDead())
            state = next
            commands.filterIsInstance<PolicyCommand.HoldCheck>().singleOrNull()?.let { delays += it.delayMs }
        }
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(state.snapshot.hold).isEqualTo(LinkHold.ALL_SILENT)
        assertThat(state.silentRounds).isEqualTo(4)
        // Started under 10 minutes ago: no stale-core restart; the pauses only grow.
        assertThat(delays).hasSize(4)
        assertThat(delays.zipWithNext().all { (a, b) -> b > a }).isTrue()
        assertThat(delays.last()).isAtLeast(8 * 60_000L)
    }

    @Test
    fun aLongSilentCoreIsStartedOnceMoreAtTheSecondRound() {
        var now = 0L
        val context = ctx(nowMs = { now })
        var state = connected()
        now = 11 * 60_000L
        state = machine.reduce(state, context, tunnelDead()).first
        val (next, commands) = machine.reduce(state, context, tunnelDead())
        val retry = commands.filterIsInstance<PolicyCommand.ScheduleRetry>().single()
        assertThat(retry.reason).isEqualTo(PolicyCommand.REASON_STALE_CORE)
        assertThat(next.silentRounds).isEqualTo(2)
    }

    @Test
    fun aNewNetworkForgetsTheSilentRounds() {
        var now = 0L
        val context = ctx(nowMs = { now })
        var state = connected()
        repeat(3) {
            now += 60_000
            state = machine.reduce(state, context, tunnelDead()).first
        }
        assertThat(state.snapshot.hold).isEqualTo(LinkHold.ALL_SILENT)
        val (next, commands) = machine.reduce(state, context, PolicyEvent.NetworkChanged)
        assertThat(commands).isEmpty()
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
        assertThat(next.snapshot.hold).isNull()
        assertThat(next.silentRounds).isEqualTo(0)
        // On the new network the count starts again: the next silent round is round 1 with a short pause.
        now += 60_000
        val (afterDead, again) = machine.reduce(next, context, tunnelDead())
        assertThat(afterDead.silentRounds).isEqualTo(1)
        assertThat(afterDead.snapshot.hold).isEqualTo(LinkHold.JAMMED)
        assertThat(again.filterIsInstance<PolicyCommand.HoldCheck>().single().delayMs).isAtMost(72_000L)
    }

    @Test
    fun aNetworkChangeDoesNotWakeAStoppedSession() {
        val stopped = machine.reduce(connected(), ctx(), PolicyEvent.UserDisconnect).first
        val (next, commands) = machine.reduce(stopped, ctx(), PolicyEvent.NetworkChanged)
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(commands).isEmpty()
    }

    @Test
    fun aReplacementThatCannotStartKeepsTheVpnOnTheOldConfig() {
        val penalties = ConfigPenalties(random = Random(1))
        val context = ctx(FailoverSettings(true, "g1"), sampleGroup(), penalties)
        val checking = machine.reduce(connected(), context, tunnelDead()).first
        val switching = machine.reduce(checking, context, PolicyEvent.ProbeCompleted(setOf("out-b"), setOf("out-b"))).first
        assertThat(switching.snapshot.activeOutboundId).isEqualTo("out-b")
        val (next, commands) = machine.reduce(switching, context, PolicyEvent.SwitchFailed("out-a"))
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(next.snapshot.activeOutboundId).isEqualTo("out-a")
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>().single().outboundId).isEqualTo("out-a")
        assertThat(penalties.isResting("out-b", 0L)).isTrue()
    }

    @Test
    fun aLateSwitchFailureDoesNotUndoTheUsersOwnStart() {
        val (next, commands) = machine.reduce(connected(), ctx(), PolicyEvent.SwitchFailed("out-z"))
        assertThat(next.snapshot.activeOutboundId).isEqualTo("out-a")
        assertThat(commands).isEmpty()
    }

    @Test
    fun aTunnelFailureRightAfterANetworkChangeBlamesNothing() {
        val penalties = ConfigPenalties(random = Random(1))
        val context = ctx(FailoverSettings(true, "g1"), sampleGroup(), penalties, sinceNetworkChange = 1_000L)
        val (next, commands) = machine.reduce(connected(), context, tunnelDead())
        assertThat(commands).containsExactly(PolicyCommand.HoldCheck(LinkJudge.SETTLE_RECHECK_MS))
        assertThat(next.silentRounds).isEqualTo(0)
        assertThat(penalties.isResting("out-a", 0L)).isFalse()
    }

    @Test
    fun aVerifiedTunnelTakesTheConfigOffTheRestingList() {
        val penalties = ConfigPenalties(random = Random(1))
        val context = ctx(penalties = penalties)
        var state = machine.reduce(connected(), context, tunnelDead()).first
        assertThat(penalties.isResting("out-a", 0L)).isTrue()
        state = machine.reduce(state, context, PolicyEvent.TunnelVerified).first
        assertThat(penalties.entry("out-a")).isNull()
        assertThat(state.snapshot.hold).isNull()
        assertThat(state.silentRounds).isEqualTo(0)
    }

    @Test
    fun allConfigsSilentAfterCoreFailuresUsesRareChecks() {
        var state = connected()
        val context = ctx()
        var last: PolicyCommand.ScheduleRetry? = null
        repeat(8) {
            val step = machine.reduce(state, context, PolicyEvent.EngineFailed(ConnectionCause.DialTimeout(5)))
            state = step.first
            last = step.second.filterIsInstance<PolicyCommand.ScheduleRetry>().single()
        }
        assertThat(state.snapshot.hold).isEqualTo(LinkHold.ALL_SILENT)
        assertThat(last!!.reason).isEqualTo(PolicyCommand.REASON_RECHECK)
        assertThat(last!!.delayMs).isAtLeast(4 * 60_000L)
    }

    @Test
    fun watchdogUsesFakeClock() {
        val clock = app.lernet.engine.FakeClock(10_000)
        val watchdog = Watchdog(clock, timeoutMs = 20_000)
        watchdog.markSuccess()
        clock.advance(19_999)
        assertThat(watchdog.isExpired()).isFalse()
        clock.advance(1)
        assertThat(watchdog.isExpired()).isTrue()
    }

    private fun tunnelDead() = PolicyEvent.EngineFailed(ConnectionCause.TunnelHealthFailed("timeout"))

    private fun connected(): MachineState {
        var state = MachineState.idle()
        state = machine.reduce(state, ctx(), startEvent()).first
        state = machine.reduce(state, ctx(), PolicyEvent.EngineStarted).first
        return machine.reduce(state, ctx(), PolicyEvent.OutboundReady).first
    }

    private fun healthyStatus() = PolicyEvent.EngineStatus(
        uplinkBps = 128,
        downlinkBps = 512,
        uplinkTotal = 128,
        downlinkTotal = 512,
        connectionsOut = 1,
    )

    private fun status(downlinkTotal: Long, proxiedReplies: Long?) = PolicyEvent.EngineStatus(
        uplinkBps = 10,
        downlinkBps = 10,
        uplinkTotal = downlinkTotal / 10,
        downlinkTotal = downlinkTotal,
        connectionsOut = 2,
        proxiedReplies = proxiedReplies,
    )

    private fun zeroStatus() = PolicyEvent.EngineStatus(
        uplinkBps = 0,
        downlinkBps = 0,
        uplinkTotal = 0,
        downlinkTotal = 0,
        connectionsOut = 0,
    )

    private fun startEvent() = PolicyEvent.StartRequested(
        profileId = "p1",
        outboundId = "out-a",
        mode = RunMode.FULL_VPN,
        compiledJson = "{}",
    )

    private fun ctx(
        failover: FailoverSettings = FailoverSettings(false, null),
        group: ManualFailoverGroup? = null,
        penalties: ConfigPenalties = ConfigPenalties(random = Random(7)),
        nowMs: () -> Long = { 0L },
        sinceNetworkChange: Long = Long.MAX_VALUE,
        budgetWaitMs: Long = 0L,
    ) = MachineContext(
        reconnect, failover, group,
        msSinceNetworkChange = { sinceNetworkChange },
        nowMs = nowMs,
        penalties = penalties,
        budgetWaitMs = { budgetWaitMs },
        random = Random(11),
    )

    private fun folder() = ctx(failover = FailoverSettings(enabled = true, groupId = "g1"), group = sampleGroup())

    private fun sampleGroup() = ManualFailoverGroup(
        id = "g1",
        name = "Запас",
        outboundIds = listOf("out-a", "out-b"),
        labels = mapOf("out-a" to "A", "out-b" to "B"),
    )
}
