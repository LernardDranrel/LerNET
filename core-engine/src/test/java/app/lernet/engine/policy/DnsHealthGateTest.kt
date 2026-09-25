package app.lernet.engine.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * After Connected, DNS must come up. Bytes / TCP dials do not count.
 * Idle after a first dns-ok must not look dead.
 */
class DnsHealthGateTest {
    @Test
    fun waitingFirstOkPastDeadlineSoftReloadsOnce() {
        val gate = DnsHealthGate()
        assertThat(gate.phase).isEqualTo(DnsHealthPhase.WaitingFirstOk)
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.SoftReload)
        assertThat(gate.reloadsUsed).isEqualTo(1)
    }

    @Test
    fun secondDeadlineWithoutOkFails() {
        val gate = DnsHealthGate()
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.SoftReload)
        gate.resetPhaseForReload()
        assertThat(gate.phase).isEqualTo(DnsHealthPhase.WaitingFirstOk)
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.Fail)
    }

    @Test
    fun dnsOkHoldsThroughLaterDeadlines() {
        val gate = DnsHealthGate()
        gate.onDnsOk()
        assertThat(gate.phase).isEqualTo(DnsHealthPhase.Healthy)
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.Hold)
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.Hold)
    }

    @Test
    fun bytesDoNotCountAsDnsOk() {
        val gate = DnsHealthGate()
        gate.onTrafficBytes()
        assertThat(gate.phase).isEqualTo(DnsHealthPhase.WaitingFirstOk)
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.SoftReload)
    }

    @Test
    fun failAfterOkStartsRecoveryThenFails() {
        val gate = DnsHealthGate()
        gate.onDnsOk()
        gate.onDnsFail()
        assertThat(gate.phase).isEqualTo(DnsHealthPhase.RecoveringFromFail)
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.SoftReload)
        gate.resetPhaseForReload()
        gate.onDnsFail()
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.Fail)
    }

    @Test
    fun okDuringRecoveryReturnsToHealthy() {
        val gate = DnsHealthGate()
        gate.onDnsOk()
        gate.onDnsFail()
        gate.onDnsOk()
        assertThat(gate.phase).isEqualTo(DnsHealthPhase.Healthy)
        assertThat(gate.onDeadline()).isEqualTo(DnsHealthAction.Hold)
    }

    @Test
    fun userConnectResetsReloads() {
        val gate = DnsHealthGate()
        gate.onDeadline()
        gate.resetForConnect()
        assertThat(gate.reloadsUsed).isEqualTo(0)
        assertThat(gate.phase).isEqualTo(DnsHealthPhase.WaitingFirstOk)
    }
}
