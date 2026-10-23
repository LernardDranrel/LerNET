package app.lernet.engine.net

import app.lernet.config.net.LocalProxyAccess
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BackupProbeTest {
    @Test
    fun backgroundCheckWithVpnOnDialsDirectWithoutRidingLiveTunnel() {
        runBlocking {
            val dialed = mutableListOf<OutboundEndpoint>()
            val probe = TunnelFirstBackupProbe(
                direct = OutboundDialer { target, _ ->
                    dialed += target
                    Result.success(Unit)
                },
                timeoutMs = 1_000,
            )
            val endpoint = OutboundEndpoint("backup.example.com", 443, ProbeTransport.TCP)
            // Tunnel is up — old code returned SKIP (null) or TUNNEL (throughTunnel=true).
            val tunnel = LocalProxyAccess(2081, "lernet-test", "password-for-test-only")
            val result = probe.check(endpoint, tunnel, userAsked = false)
            assertThat(result).isNotNull()
            assertThat(result!!.throughTunnel).isFalse()
            assertThat(result.ok).isTrue()
            assertThat(dialed).containsExactly(endpoint)
        }
    }

    @Test
    fun userAskedCheckAlsoDialsDirectWhenTunnelIsUp() {
        runBlocking {
            val dialed = mutableListOf<String>()
            val probe = TunnelFirstBackupProbe(
                direct = OutboundDialer { target, _ ->
                    dialed += target.host
                    Result.success(Unit)
                },
            )
            val tunnel = LocalProxyAccess(2081, "lernet-test", "password-for-test-only")
            val result = probe.check(OutboundEndpoint("other.example.com", 8443), tunnel, userAsked = true)
            assertThat(result!!.throughTunnel).isFalse()
            assertThat(dialed).containsExactly("other.example.com")
        }
    }
}
