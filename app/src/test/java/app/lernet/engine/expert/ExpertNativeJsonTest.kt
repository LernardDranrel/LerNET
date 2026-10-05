package app.lernet.engine.expert

import app.lernet.engine.policy.PolicyControlCapabilities
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ExpertNativeJsonTest {
    @Test
    fun malformedOptionalValuesAreSkippedWithoutSerializingTheirContents() {
        val body = ExpertNativeJson.objectValue(
            """{"reason":{"password":"private-value"},"process":[],"active_flows":{},
                "revision":"3","running":"true","packages":[{},["private-value"],"org.example.app",12]}""",
            8_192, "Native optional fixture",
        )
        assertThat(ExpertNativeJson.string(body, "reason")).isNull()
        assertThat(ExpertNativeJson.string(body, "process")).isNull()
        assertThat(ExpertNativeJson.int(body, "active_flows")).isNull()
        assertThat(ExpertNativeJson.long(body, "revision")).isNull()
        assertThat(ExpertNativeJson.boolean(body, "running")).isNull()
        assertThat(ExpertNativeJson.strings(body["packages"], 128)).containsExactly("org.example.app")
    }

    @Test
    fun actualNumericAndBooleanEvidenceRemainsTyped() {
        val body = ExpertNativeJson.objectValue("""{"revision":3,"running":true,"closed":false,"active_flows":2}""", 8_192, "Fixture")
        assertThat(ExpertNativeJson.long(body, "revision")).isEqualTo(3L)
        assertThat(ExpertNativeJson.boolean(body, "running")).isTrue()
        assertThat(ExpertNativeJson.boolean(body, "closed")).isFalse()
        assertThat(ExpertNativeJson.int(body, "active_flows")).isEqualTo(2)
    }

    @Test
    fun malformedCriticalAckFailsWithFinitePlainMessage() {
        val failure = assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"instance_id":{"password":"private-value"},"interface_id":"tun","revision":1}""")
        }
        assertThat(failure.message).isEqualTo("Native Expert acknowledgement has no valid instance_id")
        assertThat(failure.message).doesNotContain("private-value")
        assertThat(failure.cause).isNull()
    }

    @Test
    fun malformedJsonDoesNotExposeParserInputInFailureOrCause() {
        val failure = assertThrows(IllegalStateException::class.java) {
            ExpertNativeAck.decode("""{"password":"private-value",broken}""")
        }
        assertThat(failure.message).isEqualTo("Native Expert acknowledgement is not a valid object")
        assertThat(failure.cause).isNull()
    }

    @Test
    fun objectCapabilitiesCannotClaimNativeSupport() {
        val capabilities = ExpertNativeCapabilityProbe.decode("""{"protocol_version":{"password":"private-value"}}""")
        assertThat(capabilities).isEqualTo(PolicyControlCapabilities.RESTART_ONLY)
        val primitiveVersion = ExpertNativeCapabilityProbe.decode("""{"protocol_version":1,"preserves_tun":{},"hot_policy_apply":true}""")
        assertThat(primitiveVersion.preservesTun).isFalse()
    }

    @Test
    fun malformedCloseProofCannotReleaseUncertainResource() {
        val fixture = ExpertNativeBridgeTest.CloseNativeFixture(failFirstClose = true)
        val session = ExpertNativeSession(fixture)
        assertThrows(IllegalStateException::class.java) { session.close() }
        fixture.statusJson = """{"close_confirmed":{"password":"private-value"}}"""
        val failure = assertThrows(IllegalStateException::class.java) { session.close() }
        assertThat(failure.message).isEqualTo("Native Expert resource closure is unconfirmed")
        assertThat(fixture.closeCalls).isEqualTo(1)
    }
}
