package app.lernet.engine.policy

import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionState
import app.lernet.engine.FakeClock
import app.lernet.engine.RunMode
import com.google.common.truth.Truth.assertThat
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
    fun zeroTrafficWhileConnectedIsUnhealthy() {
        var state = connected()
        state = machine.reduce(state, ctx(), zeroStatus()).first
        val (next, commands) = machine.reduce(state, ctx(), PolicyEvent.WatchdogMiss)
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(next.snapshot.cause).isInstanceOf(ConnectionCause.WatchdogTimeout::class.java)
        assertThat(next.softReloads).isEqualTo(1)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
    }

    @Test
    fun watchdogWhileConnectedKeepsRetryingAfterRecovery() {
        var state = connected()
        val first = machine.reduce(state, ctx(), PolicyEvent.WatchdogMiss)
        state = first.first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(first.second.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()

        state = machine.reduce(state, ctx(), PolicyEvent.EngineStarted).first
        state = machine.reduce(state, ctx(), PolicyEvent.OutboundReady).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTED)
        state = machine.reduce(state, ctx(), healthyStatus()).first
        assertThat(state.softReloads).isEqualTo(0)

        val second = machine.reduce(state, ctx(), PolicyEvent.WatchdogMiss)
        assertThat(second.first.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(second.first.snapshot.cause).isInstanceOf(ConnectionCause.WatchdogTimeout::class.java)
        assertThat(second.second.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
        assertThat(second.second.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
    }

    @Test
    fun reconnectsSameOutboundWithBackoffFromFakeClockMath() {
        val clock = FakeClock(0)
        var state = connected()
        val ctx = ctx()
        val (retrying, commands) = machine.reduce(
            state,
            ctx,
            PolicyEvent.EngineFailed(ConnectionCause.TlsFailure("hang")),
        )
        state = retrying
        val retry = commands.filterIsInstance<PolicyCommand.ScheduleRetry>().single()
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(retry.outboundId).isEqualTo("out-a")
        assertThat(retry.delayMs).isEqualTo(1_000)
        clock.advance(retry.delayMs)
        assertThat(clock.nowMs()).isEqualTo(1_000)
        val second = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.DialFailure("rst")))
        val retry2 = second.second.filterIsInstance<PolicyCommand.ScheduleRetry>().single()
        assertThat(retry2.outboundId).isEqualTo("out-a")
        assertThat(retry2.delayMs).isEqualTo(2_000)
        assertThat(second.first.snapshot.activeOutboundId).isEqualTo("out-a")
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
    fun watchdogUsesFakeClock() {
        val clock = FakeClock(10_000)
        val watchdog = Watchdog(clock, timeoutMs = 20_000)
        watchdog.markSuccess()
        clock.advance(19_999)
        assertThat(watchdog.isExpired()).isFalse()
        clock.advance(1)
        assertThat(watchdog.isExpired()).isTrue()
        var state = connected()
        state = machine.reduce(state, ctx(), PolicyEvent.WatchdogMiss).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(state.snapshot.cause).isInstanceOf(ConnectionCause.WatchdogTimeout::class.java)
        assertThat(state.snapshot.activeOutboundId).isEqualTo("out-a")
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
    fun engineStartFailedIsNotRetried() {
        val (next, commands) = machine.reduce(
            connected(),
            ctx(),
            PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed("setup exploded")),
        )
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.FAILED)
        assertThat(commands.filterIsInstance<PolicyCommand.ScheduleRetry>()).isEmpty()
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
    fun dnsHealthTimeoutSoftReloadsThenRetries() {
        var state = connected()
        val first = machine.reduce(state, ctx(), PolicyEvent.DnsHealthTimeout)
        state = first.first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(state.snapshot.cause).isInstanceOf(ConnectionCause.DnsStalled::class.java)
        assertThat(first.second.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
        assertThat(state.softReloads).isEqualTo(1)

        state = machine.reduce(state, ctx(), PolicyEvent.EngineStarted).first
        state = machine.reduce(state, ctx(), PolicyEvent.OutboundReady).first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTED)

        val second = machine.reduce(state, ctx(), PolicyEvent.DnsHealthTimeout)
        assertThat(second.first.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(second.first.snapshot.cause)
            .isInstanceOf(ConnectionCause.DnsUnreachable::class.java)
        assertThat(second.second.filterIsInstance<PolicyCommand.ScheduleRetry>()).isNotEmpty()
        assertThat(second.second.filterIsInstance<PolicyCommand.StopEngine>()).isEmpty()
    }

    @Test
    fun userDisconnectEndsDisconnected() {
        var state = connected()
        val (next, commands) = machine.reduce(state, ctx(), PolicyEvent.UserDisconnect)
        assertThat(next.snapshot.state).isEqualTo(ConnectionState.DISCONNECTED)
        assertThat(commands.filterIsInstance<PolicyCommand.StopEngine>()).isNotEmpty()
    }

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
    ) = MachineContext(reconnect, failover, group)

    private fun sampleGroup() = ManualFailoverGroup(
        id = "g1",
        name = "Запас",
        outboundIds = listOf("out-a", "out-b"),
        labels = mapOf("out-a" to "A", "out-b" to "B"),
    )
}
