package app.lernet.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectionCauseTest {
    @Test
    fun onlyRuntimeNetworkCausesAreRetryable() {
        assertThat(ConnectionCause.TlsFailure("hang").isRetryable()).isTrue()
        assertThat(ConnectionCause.HandshakeFailure("hs").isRetryable()).isTrue()
        assertThat(ConnectionCause.ConnectionReset("rst").isRetryable()).isTrue()
        assertThat(ConnectionCause.DialFailure("refused").isRetryable()).isTrue()
        assertThat(ConnectionCause.DialTimeout(5).isRetryable()).isTrue()
        assertThat(ConnectionCause.WatchdogTimeout(20).isRetryable()).isTrue()
        assertThat(ConnectionCause.DnsStalled(8).isRetryable()).isTrue()
        assertThat(ConnectionCause.DnsUnreachable(8).isRetryable()).isTrue()
        assertThat(ConnectionCause.ConnectTimeout(15).isRetryable()).isTrue()
        assertThat(ConnectionCause.OutboundUnreachable("151.1.2.3:443").isRetryable()).isTrue()
        assertThat(ConnectionCause.InvalidConfig(listOf("unknown transport type: xhttp")).isRetryable()).isFalse()
        assertThat(ConnectionCause.EngineStartFailed("decode config").isRetryable()).isFalse()
        assertThat(ConnectionCause.InvalidRouteTree(listOf("x")).isRetryable()).isFalse()
        assertThat(ConnectionCause.VpnPermissionDenied.isRetryable()).isFalse()
        assertThat(ConnectionCause.ServiceRevoked.isRetryable()).isFalse()
    }

    @Test
    fun invalidConfigLabelIncludesDetails() {
        val cause = ConnectionCause.InvalidConfig(listOf("unknown transport type: xhttp"))
        assertThat(cause.labelRu()).contains("xhttp")
    }

    @Test
    fun failedCardSplitsHumanTitleFromTechnicalDetail() {
        val cause = ConnectionCause.InvalidConfig(listOf("legacy inbound fields are deprecated"))
        assertThat(cause.titleRu()).isEqualTo("Некорректный конфиг")
        assertThat(cause.technicalDetail()).isEqualTo("legacy inbound fields are deprecated")
        assertThat(cause.labelRu()).contains("legacy inbound")
    }

    @Test
    fun outboundUnreachableIsHumanReadableAndRetryable() {
        val cause = ConnectionCause.OutboundUnreachable("151.1.2.3:443: refused")
        assertThat(cause.titleRu()).isEqualTo("Узел недоступен")
        assertThat(cause.technicalDetail()).contains("151.1.2.3")
        assertThat(cause.isRetryable()).isTrue()
    }

    @Test
    fun connectTimeoutIsHumanReadableAndRetryable() {
        val cause = ConnectionCause.ConnectTimeout(15)
        assertThat(cause.titleRu()).isEqualTo("Таймаут подключения")
        assertThat(cause.technicalDetail()).contains("15")
        assertThat(cause.isRetryable()).isTrue()
    }

    @Test
    fun watchdogTimeoutMentionsZeroTraffic() {
        val cause = ConnectionCause.WatchdogTimeout(20)
        assertThat(cause.titleRu()).isEqualTo("Нет трафика")
        assertThat(cause.technicalDetail()).contains("20")
        assertThat(cause.technicalDetail()).contains("прироста")
    }

    @Test
    fun dnsUnreachableIsClearAndRetryable() {
        val stalled = ConnectionCause.DnsStalled(8)
        assertThat(stalled.titleRu()).isEqualTo("DNS не отвечает")
        assertThat(stalled.technicalDetail()).contains("перезапуск")
        val dead = ConnectionCause.DnsUnreachable(8)
        assertThat(dead.titleRu()).isEqualTo("DNS не отвечает")
        assertThat(dead.technicalDetail()).contains("после перезапуска")
        assertThat(dead.isRetryable()).isTrue()
    }
}
