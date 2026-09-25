package app.lernet.engine.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SniffRematchTest {
    @Test
    fun tcpAfterSniffHitsFinalProxy() {
        assertThat(SniffRematch.target(protocol = "tls", port = 443, privateIp = false, finalTag = "proxy"))
            .isEqualTo("proxy")
        assertThat(SniffRematch.target(protocol = null, port = 443, privateIp = false, finalTag = "proxy"))
            .isEqualTo("proxy")
        assertThat(SniffRematch.note("proxy")).isEqualTo("sniff rematch TCP → outbound=proxy")
    }

    @Test
    fun dnsProtocolOrPort53Hijacks() {
        assertThat(SniffRematch.target(protocol = "dns", port = 853, privateIp = false, finalTag = "proxy"))
            .isEqualTo("hijack-dns")
        assertThat(SniffRematch.target(protocol = null, port = 53, privateIp = false, finalTag = "proxy"))
            .isEqualTo("hijack-dns")
        assertThat(SniffRematch.target(protocol = "tls", port = 53, privateIp = false, finalTag = "proxy"))
            .isEqualTo("hijack-dns")
    }

    @Test
    fun privateIpStaysDirect() {
        assertThat(SniffRematch.target(protocol = "tls", port = 443, privateIp = true, finalTag = "proxy"))
            .isEqualTo("direct")
    }

    @Test
    fun libboxTcpDialLineIsRematchInfo() {
        val line = "outbound/vless[proxy]: outbound connection to 216.58.198.3:443"
        assertThat(SniffRematch.fromLibboxLine(line)).isEqualTo("sniff rematch TCP outbound=proxy dest=216.58.198.3:443")
    }

    @Test
    fun packetDialIsNotTcpRematch() {
        val line = "outbound/vless[proxy]: outbound packet connection to 216.58.198.3:443"
        assertThat(SniffRematch.fromLibboxLine(line)).isNull()
    }
}
