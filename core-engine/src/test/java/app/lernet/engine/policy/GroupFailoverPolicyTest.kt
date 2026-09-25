package app.lernet.engine.policy

import app.lernet.engine.ConnectionCause
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GroupFailoverPolicyTest {
    private val machine = ConnectionPolicyMachine()
    private val group = ManualFailoverGroup(
        id = "spare",
        name = "Чёрный день",
        outboundIds = listOf("out-a", "out-b", "out-c"),
        labels = mapOf("out-a" to "A", "out-b" to "B", "out-c" to "C"),
    )

    @Test
    fun notEligibleWhenDisabled() {
        assertThat(
            GroupFailoverPolicy.isEligible(FailoverSettings(false, "spare"), group, "out-a"),
        ).isFalse()
    }

    @Test
    fun notEligibleWithoutOtherMembers() {
        val singleton = group.copy(outboundIds = listOf("out-a"))
        assertThat(
            GroupFailoverPolicy.isEligible(FailoverSettings(true, "spare"), singleton, "out-a"),
        ).isFalse()
    }

    @Test
    fun eligibleWhenManualGroupHasSpare() {
        assertThat(
            GroupFailoverPolicy.isEligible(FailoverSettings(true, "spare"), group, "out-a"),
        ).isTrue()
        assertThat(GroupFailoverPolicy.candidates(group, "out-a", setOf("out-b")))
            .containsExactly("out-c")
            .inOrder()
    }

    @Test
    fun firstAliveKeepsGroupOrder() {
        assertThat(GroupFailoverPolicy.firstAlive(listOf("out-b", "out-c"), setOf("out-c", "out-b")))
            .isEqualTo("out-b")
        assertThat(GroupFailoverPolicy.firstAlive(listOf("out-b", "out-c"), emptySet())).isNull()
    }

    @Test
    fun machineFailsOverToFirstAliveInsideGroupOnly() {
        var state = connected("out-a")
        val ctx = MachineContext(
            reconnect = ReconnectSettings(maxAttempts = 2),
            failover = FailoverSettings(true, "spare"),
            group = group,
        )
        state = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.TlsFailure("dead"))).first
        val probe = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.TlsFailure("dead")))
        assertThat(probe.second.filterIsInstance<PolicyCommand.ProbeGroup>().single().outboundIds)
            .containsExactly("out-b", "out-c")
            .inOrder()
        assertThat(probe.second.filterIsInstance<PolicyCommand.ProbeGroup>().single().outboundIds)
            .doesNotContain("out-outside")
        state = probe.first
        val switched = machine.reduce(state, ctx, PolicyEvent.ProbeCompleted(setOf("out-c", "out-outside")))
        state = switched.first
        assertThat(state.snapshot.state).isEqualTo(ConnectionState.CONNECTING)
        assertThat(state.snapshot.activeOutboundId).isEqualTo("out-c")
        assertThat(state.snapshot.banner?.groupName).isEqualTo("Чёрный день")
        assertThat(state.snapshot.banner?.fromName).isEqualTo("A")
        assertThat(state.snapshot.banner?.toName).isEqualTo("C")
        assertThat(switched.second.filterIsInstance<PolicyCommand.StartEngine>().single().outboundId)
            .isEqualTo("out-c")
    }

    @Test
    fun neverPicksOutsideGroupEvenIfAlive() {
        var state = connected("out-a")
        val ctx = MachineContext(
            reconnect = ReconnectSettings(maxAttempts = 1),
            failover = FailoverSettings(true, "spare"),
            group = group,
        )
        state = machine.reduce(state, ctx, PolicyEvent.EngineFailed(ConnectionCause.DialFailure("x"))).first
        val afterProbe = machine.reduce(state, ctx, PolicyEvent.ProbeCompleted(setOf("out-outside"))).first
        assertThat(afterProbe.snapshot.state).isEqualTo(ConnectionState.RECONNECTING)
        assertThat(afterProbe.snapshot.cause).isInstanceOf(ConnectionCause.FailoverExhausted::class.java)
        assertThat(afterProbe.snapshot.activeOutboundId).isEqualTo("out-a")
    }

    private fun connected(outboundId: String): MachineState {
        var state = MachineState.idle()
        val ctx = MachineContext(ReconnectSettings(), FailoverSettings(), group)
        state = machine.reduce(
            state,
            ctx,
            PolicyEvent.StartRequested("p1", outboundId, RunMode.PROXY, "{}"),
        ).first
        state = machine.reduce(state, ctx, PolicyEvent.EngineStarted).first
        return machine.reduce(state, ctx, PolicyEvent.OutboundReady).first
    }
}
