package app.lernet.engine.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DestPingTest {
    @Test
    fun parseHostPortFromDestination() {
        assertThat(DestPing.parseHostPort("149.154.167.91:443")).isEqualTo("149.154.167.91" to 443)
        assertThat(DestPing.parseHostPort("example.com:853")).isEqualTo("example.com" to 853)
        assertThat(DestPing.parseHostPort("no-port")).isNull()
    }

    @Test
    fun tcpSuccessReturnsRtt() {
        var now = 10L
        val rtt = DestPing.tcp(
            host = "example.com",
            port = 443,
            timeoutMs = 1_000,
            nowMs = { now.also { now += 17 } },
            connect = { _, _, _ -> },
        )
        assertThat(rtt.isSuccess).isTrue()
        assertThat(rtt.getOrNull()).isEqualTo(17L)
    }

    @Test
    fun tcpFailureIsError() {
        val rtt = DestPing.tcp(
            host = "none.invalid",
            port = 9,
            timeoutMs = 200,
            nowMs = { 0L },
            connect = { _, _, _ -> error("refused") },
        )
        assertThat(rtt.isFailure).isTrue()
        assertThat(rtt.exceptionOrNull()?.message).contains("refused")
    }
}
