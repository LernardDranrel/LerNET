package app.lernet.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EngineErrorMapperTest {
    @Test
    fun mapsTlsHandshakeResetDialAndPermission() {
        assertThat(EngineErrorMapper.map("tls: bad certificate")).isInstanceOf(ConnectionCause.TlsFailure::class.java)
        assertThat(EngineErrorMapper.map("REALITY handshake failed"))
            .isInstanceOf(ConnectionCause.HandshakeFailure::class.java)
        assertThat(EngineErrorMapper.map("read: connection reset by peer"))
            .isInstanceOf(ConnectionCause.ConnectionReset::class.java)
        assertThat(EngineErrorMapper.map("dial tcp 1.2.3.4:443: connect: connection refused"))
            .isInstanceOf(ConnectionCause.DialFailure::class.java)
        assertThat(EngineErrorMapper.map("android: missing vpn permission"))
            .isEqualTo(ConnectionCause.VpnPermissionDenied)
        assertThat(EngineErrorMapper.map("android: the application is not prepared or is revoked"))
            .isEqualTo(ConnectionCause.ServiceRevoked)
        assertThat(EngineErrorMapper.map("android: VpnService.protect returned false (not prepared or revoked) fd=4"))
            .isEqualTo(ConnectionCause.ServiceRevoked)
        assertThat(EngineErrorMapper.map("protect=false revoked fd=4"))
            .isEqualTo(ConnectionCause.ServiceRevoked)
        assertThat(EngineErrorMapper.map("android: VpnService missing in TUN mode (not prepared or revoked)"))
            .isEqualTo(ConnectionCause.ServiceRevoked)
        assertThat(EngineErrorMapper.map("protect(9) FAILED → Failed"))
            .isEqualTo(ConnectionCause.ServiceRevoked)
    }

    @Test
    fun mapsTimeoutAndUnknown() {
        val timeout = EngineErrorMapper.map("i/o timeout after 5s")
        assertThat(timeout).isInstanceOf(ConnectionCause.DialTimeout::class.java)
        assertThat((timeout as ConnectionCause.DialTimeout).timeoutSeconds).isEqualTo(5)
        assertThat(EngineErrorMapper.map("kernel panic")).isInstanceOf(ConnectionCause.EngineStartFailed::class.java)
    }

    @Test
    fun mapsDecodeAndUnknownTransportToInvalidConfig() {
        val decode = EngineErrorMapper.map(
            "decode config: outbounds[0].transport: unknown transport type: xhttp",
        )
        assertThat(decode).isInstanceOf(ConnectionCause.InvalidConfig::class.java)
        assertThat((decode as ConnectionCause.InvalidConfig).details.single()).contains("xhttp")
        assertThat(EngineErrorMapper.map("parse config: unknown field"))
            .isInstanceOf(ConnectionCause.InvalidConfig::class.java)
        assertThat(
            EngineErrorMapper.map("legacy DNS server formats are deprecated in sing-box 1.12.0 and removed in sing-box 1.14.0"),
        ).isInstanceOf(ConnectionCause.InvalidConfig::class.java)
        assertThat(
            EngineErrorMapper.map("legacy inbound fields are deprecated in sing-box 1.11.0 and removed in sing-box 1.13.0"),
        ).isInstanceOf(ConnectionCause.InvalidConfig::class.java)
    }
}
