package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProbeTransportTest {
    @Test
    fun picksACheckThatSuitsTheProtocol() {
        assertThat(OutboundEndpoint.parse("""{"type":"vless","server":"h","server_port":443}""")?.transport)
            .isEqualTo(ProbeTransport.TCP)
        assertThat(OutboundEndpoint.parse("""{"type":"hysteria2","server":"h","server_port":443}""")?.transport)
            .isEqualTo(ProbeTransport.QUIC)
        assertThat(OutboundEndpoint.parse("""{"type":"tuic","server":"h","server_port":443}""")?.transport)
            .isEqualTo(ProbeTransport.QUIC)
        assertThat(
            OutboundEndpoint.parse("""{"type":"hysteria2","server":"h","server_port":443,"obfs":{"type":"salamander"}}""")?.transport,
        ).isEqualTo(ProbeTransport.UNVERIFIABLE)
    }

    @Test
    fun quicRequestForcesVersionNegotiation() {
        val packet = QuicVersionProbe.request()
        assertThat(packet.size).isEqualTo(1_200)
        assertThat(packet[0].toInt() and 0xC0).isEqualTo(0xC0)
        assertThat(packet.copyOfRange(1, 5).toList()).containsExactly(0x1a.toByte(), 0x2a.toByte(), 0x3a.toByte(), 0x4a.toByte()).inOrder()
        assertThat(packet[5].toInt()).isEqualTo(8)
        assertThat(packet[14].toInt()).isEqualTo(8)
    }

    @Test
    fun recognisesVersionNegotiationReply() {
        // Real reply prefix from a sing-box hysteria2 server (see LerNET-evidence/failover).
        val reply = byteArrayOf(0xda.toByte(), 0, 0, 0, 0, 8, 1, 2, 3)
        assertThat(QuicVersionProbe.isVersionNegotiation(reply)).isTrue()
        assertThat(QuicVersionProbe.isVersionNegotiation(byteArrayOf(0x40, 0, 0, 0, 0, 8, 1))).isFalse()
        assertThat(QuicVersionProbe.isVersionNegotiation(byteArrayOf(0xc0.toByte(), 0, 0, 0, 1, 8, 1))).isFalse()
    }
}
