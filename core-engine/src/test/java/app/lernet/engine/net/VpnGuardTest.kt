package app.lernet.engine.net

import app.lernet.engine.ConnectionCause
import app.lernet.engine.EngineErrorMapper
import app.lernet.engine.nativebridge.PlatformJni
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VpnGuardTest {
    @Test
    fun tunProtectFalseIsErrorNotLogOnly() {
        val decided = VpnGuard.decideProtect(
            tunRequired = true,
            hasVpn = true,
            protectOk = false,
            fd = 9,
        )
        assertThat(decided).isInstanceOf(VpnGuard.ProtectOutcome.Fail::class.java)
        val fail = decided as VpnGuard.ProtectOutcome.Fail
        assertThat(fail.crumb).isEqualTo("protect(9) FAILED → Failed")
        val thrown = runCatching {
            PlatformJni.call("protect") {
                VpnGuard.requireProtectDecision(
                    tunRequired = true,
                    hasVpn = true,
                    protectOk = false,
                    fd = 9,
                )
            }
        }.exceptionOrNull()
        assertThat(thrown!!.javaClass).isEqualTo(Exception::class.java)
        assertThat(thrown.message).contains("protect returned false")
        assertThat(EngineErrorMapper.map(checkNotNull(thrown.message)))
            .isEqualTo(ConnectionCause.ServiceRevoked)
        assertThat(ConnectionCause.ServiceRevoked.titleRu()).isEqualTo("Система отозвала VPN")
    }

    @Test
    fun tunMissingVpnIsErrorNotLogOnly() {
        val decided = VpnGuard.decideProtect(
            tunRequired = true,
            hasVpn = false,
            protectOk = null,
            fd = 4,
        )
        assertThat(decided).isInstanceOf(VpnGuard.ProtectOutcome.Fail::class.java)
        val fail = decided as VpnGuard.ProtectOutcome.Fail
        assertThat(fail.crumb).isEqualTo("protect(4) FAILED → Failed")
        val thrown = runCatching {
            PlatformJni.call("protect") {
                VpnGuard.requireProtectDecision(
                    tunRequired = true,
                    hasVpn = false,
                    protectOk = null,
                    fd = 4,
                )
            }
        }.exceptionOrNull()
        assertThat(thrown!!.javaClass).isEqualTo(Exception::class.java)
        assertThat(thrown.message).isEqualTo(VpnGuard.MISSING_VPN_TUN)
        assertThat(EngineErrorMapper.map(checkNotNull(thrown.message)))
            .isEqualTo(ConnectionCause.ServiceRevoked)
    }

    @Test
    fun proxyMissingVpnIsSkipNotError() {
        val decided = VpnGuard.decideProtect(
            tunRequired = false,
            hasVpn = false,
            protectOk = null,
            fd = 1,
        )
        assertThat(decided).isEqualTo(VpnGuard.ProtectOutcome.SkipProxy)
        val passed = PlatformJni.call("protect") {
            VpnGuard.requireProtectDecision(
                tunRequired = false,
                hasVpn = false,
                protectOk = null,
                fd = 1,
            )
        }
        assertThat(passed).isEqualTo(VpnGuard.ProtectOutcome.SkipProxy)
    }

    @Test
    fun tunProtectTrueIsOk() {
        assertThat(
            VpnGuard.decideProtect(tunRequired = true, hasVpn = true, protectOk = true, fd = 3),
        ).isEqualTo(VpnGuard.ProtectOutcome.Ok)
    }

    @Test
    fun establishNullThrowsCheckedException() {
        val thrown = runCatching { VpnGuard.requireEstablished<Any>(null) }.exceptionOrNull()
        assertThat(thrown!!.javaClass).isEqualTo(Exception::class.java)
        assertThat(thrown.message).isEqualTo(VpnGuard.ESTABLISH_NULL)
        assertThat(VpnGuard.crumbEstablishFailed()).isEqualTo("establish() FAILED → Failed")
        val viaJni = runCatching {
            PlatformJni.call("openTun") { VpnGuard.requireEstablished<Any>(null) }
        }.exceptionOrNull()
        assertThat(EngineErrorMapper.map(checkNotNull(viaJni!!.message)))
            .isEqualTo(ConnectionCause.ServiceRevoked)
    }

    @Test
    fun establishNonNullReturnsSameInstance() {
        val pfd = Any()
        assertThat(VpnGuard.requireEstablished(pfd)).isSameInstanceAs(pfd)
    }

    @Test
    fun jniRunMustNotBeUsedForProtectFalse() {
        var swallowed = false
        PlatformJni.run("protect", { _, _ -> swallowed = true }) {
            VpnGuard.requireProtectDecision(
                tunRequired = true,
                hasVpn = true,
                protectOk = false,
                fd = 1,
            )
        }
        assertThat(swallowed).isTrue()
    }
}
