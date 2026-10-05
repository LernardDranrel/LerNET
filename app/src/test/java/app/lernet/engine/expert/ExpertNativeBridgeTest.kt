package app.lernet.engine.expert

import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.PolicyControlCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpertNativeBridgeTest {
    @Test
    fun missingCapabilitiesAreConservative() {
        assertThat(ExpertNativeCapabilityProbe.decode("{}"))
            .isEqualTo(PolicyControlCapabilities.RESTART_ONLY)
    }

    @Test
    fun textTrueDoesNotAdvertiseNativeCapability() {
        val raw = """{"protocol_version":1,"hot_policy_apply":true,"preserves_tun":"true"}"""
        assertThat(ExpertNativeCapabilityProbe.decode(raw).preservesTun)
            .isFalse()
    }

    @Test
    fun unknownNativeProtocolCannotEnableHotPolicyApply() {
        val raw = """{"protocol_version":2,"hot_policy_apply":true,"preserves_tun":true,
            "independent_exit_lifecycle":true,"atomic_prepare_commit":true,"bounded_first_flow_wait":true}"""
        assertThat(ExpertNativeCapabilityProbe.decode(raw)).isEqualTo(PolicyControlCapabilities.RESTART_ONLY)
    }

    @Test
    fun atomicCommitWithoutDestinationRedirectDoesNotAdvertiseCompleteExpertSupport() {
        val raw = """{"protocol_version":1,"hot_policy_apply":true,"preserves_tun":true,
            "independent_exit_lifecycle":true,"atomic_prepare_commit":true,"bounded_first_flow_wait":true}"""
        assertThat(ExpertNativeCapabilityProbe.decode(raw).atomicRules).isFalse()
    }

    @Test
    fun fullNativeCapabilitySetAllowsExpertWithoutClaimingCrashGuard() {
        val raw = """{"protocol_version":1,"hot_policy_apply":true,"preserves_tun":true,
            "independent_exit_lifecycle":true,"atomic_prepare_commit":true,"bounded_first_flow_wait":true,
            "destination_redirect":true,"platform_crash_guard":false,"native_health_recovery":true}"""
        val actual = ExpertNativeCapabilityProbe.decode(raw)
        assertThat(actual.preservesTun).isTrue()
        assertThat(actual.atomicRules).isTrue()
        assertThat(actual.independentExits).isTrue()
        assertThat(actual.nativeHealthRecovery).isTrue()
    }

    @Test
    fun ackNeedsActualInstanceInterfaceAndRevision() {
        assertThat(ExpertNativeAck.decode("""{"instance_id":"session-1","interface_id":"tun:27","revision":12}"""))
            .isEqualTo(ExpertNativeAck("session-1", "tun:27", 12))
        assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"instance_id":"session-1","revision":12}""")
        }
    }

    @Test
    fun ackCannotCarryUnboundedOrSecretLikeIdentifiers() {
        assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"instance_id":"raw password text","interface_id":"tun","revision":12}""")
        }
        assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"instance_id":"session","interface_id":"tun","revision":-1}""")
        }
    }

    @Test
    fun ackIdentifiersMustBeJsonStrings() {
        assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"instance_id":12,"interface_id":"tun","revision":1}""")
        }
        assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"instance_id":"session","interface_id":true,"revision":1}""")
        }
    }

    @Test
    fun quotedRevisionCannotAcknowledgeNativeCommit() {
        assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"instance_id":"session","interface_id":"tun","revision":"12"}""")
        }
    }

    @Test
    fun readyProtocolDoesNotOverrideDegradedHttpsHealth() {
        assertThat(ExpertNativeStatusEvidence.exitPhase("ready", "degraded")).isEqualTo(ExitPhase.DEGRADED)
        assertThat(ExpertNativeStatusEvidence.exitPhase("ready", "healthy")).isEqualTo(ExitPhase.READY)
    }

    @Test
    fun healthDoesNotWakeSleepingOrRetiredNativeResources() {
        assertThat(ExpertNativeStatusEvidence.exitPhase("sleeping", "degraded")).isEqualTo(ExitPhase.SLEEPING)
        assertThat(ExpertNativeStatusEvidence.exitPhase("draining", "healthy")).isEqualTo(ExitPhase.DRAINING)
        assertThat(ExpertNativeStatusEvidence.exitPhase("stopping", "healthy")).isEqualTo(ExitPhase.DRAINING)
        assertThat(ExpertNativeStatusEvidence.exitPhase("unknown", "healthy")).isEqualTo(ExitPhase.FAILED)
    }

    @Test
    fun nativeOperationsUseAtomicallyFencedApiWithExactProbeBudget() {
        val fixture = FencedNativeFixture()
        val session = ExpertNativeSession(fixture)
        val ack = ExpertNativeAck("instance-1", "tun-27", 12)
        session.wakeExit(ack, "exit-1")
        session.sleepExit(ack, "exit-1")
        session.recoverExit(ack, "exit-1")
        session.probeExit(ack, "exit-1", "https://example.test/health", 4_000)
        assertThat(fixture.calls).containsExactly(
            listOf("wake", "instance-1", "tun-27", 12L, "exit-1"),
            listOf("sleep", "instance-1", "tun-27", 12L, "exit-1"),
            listOf("recover", "instance-1", "tun-27", 12L, "exit-1"),
            listOf("probe", "instance-1", "tun-27", 12L, "exit-1", "https://example.test/health", 4_000L),
        ).inOrder()
    }

    @Test
    fun failedCloseCannotBecomeConfirmedThroughARepeatedNil() {
        val fixture = CloseNativeFixture(failFirstClose = true)
        val session = ExpertNativeSession(fixture)
        assertThrows(IllegalStateException::class.java) { session.close() }
        assertThrows(IllegalStateException::class.java) { session.close() }
        assertThat(fixture.closeCalls).isEqualTo(1)
    }

    @Test
    fun uncertainCloseCanResolveOnlyWithActualNativeDrainProof() {
        val fixture = CloseNativeFixture(failFirstClose = true)
        val session = ExpertNativeSession(fixture)
        assertThrows(IllegalStateException::class.java) { session.close() }
        fixture.statusJson = """{"close_confirmed":"true"}"""
        assertThrows(IllegalStateException::class.java) { session.close() }
        fixture.statusJson = """{"close_confirmed":true}"""
        session.close()
        session.close()
        assertThat(fixture.closeCalls).isEqualTo(1)
    }

    @Test
    fun successfulCloseIsIdempotentWithoutCallingNativeAgain() {
        val fixture = CloseNativeFixture(failFirstClose = false)
        val session = ExpertNativeSession(fixture)
        session.close()
        session.close()
        assertThat(fixture.closeCalls).isEqualTo(1)
    }

    class CloseNativeFixture(private val failFirstClose: Boolean) {
        var closeCalls = 0
        var statusJson = """{"close_confirmed":false}"""

        fun close() {
            closeCalls++
            if (failFirstClose && closeCalls == 1) error("simulated descriptor close failure")
        }

        fun status(): String = statusJson
    }

    class FencedNativeFixture {
        val calls = mutableListOf<List<Any>>()

        fun wakeExitAt(instance: String, interfaceId: String, revision: Long, tag: String) {
            calls += listOf("wake", instance, interfaceId, revision, tag)
        }

        fun sleepExitAt(instance: String, interfaceId: String, revision: Long, tag: String) {
            calls += listOf("sleep", instance, interfaceId, revision, tag)
        }

        fun recoverExitAt(instance: String, interfaceId: String, revision: Long, tag: String) {
            calls += listOf("recover", instance, interfaceId, revision, tag)
        }

        fun probeExitAt(instance: String, interfaceId: String, revision: Long, tag: String, url: String, timeout: Long): String {
            calls += listOf("probe", instance, interfaceId, revision, tag, url, timeout)
            return "{}"
        }
    }
}
