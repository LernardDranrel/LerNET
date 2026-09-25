package app.lernet.engine.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveConnFormatTest {
    @Test
    fun formatsAppHostRulePipeStatusAndBytes() {
        val line = LiveConnFormat.line(
            LiveConn(
                id = "c1",
                app = "org.telegram.messenger",
                uid = 10123,
                destHost = "149.154.167.91",
                destPort = 443,
                domain = "telegram.org",
                outbound = "pipe-video2",
                via = LiveVia.PROXY,
                uplink = 10861,
                downlink = 13202,
                rule = "r-yt",
                status = LiveConnStatus.CLOSED,
            ),
        )
        assertThat(line).contains("org.telegram.messenger")
        assertThat(line).contains("10123")
        assertThat(line).contains("telegram.org")
        assertThat(line).contains("r-yt")
        assertThat(line).contains("video2")
        assertThat(line).contains("ok")
        assertThat(line).doesNotContain("0 B")
    }

    @Test
    fun unfinishedWhenUplinkWithoutDownlink() {
        val row = LiveConn(
            id = "c2",
            app = "app",
            uid = null,
            destHost = "example.com",
            destPort = 443,
            domain = null,
            outbound = "proxy",
            via = LiveVia.PROXY,
            uplink = 200,
            downlink = 0,
            status = LiveConnStatus.UNFINISHED,
        )
        assertThat(LiveConnFormat.unfinished(row)).isTrue()
        assertThat(LiveConnFormat.line(row)).contains("no reply")
    }

    @Test
    fun viaFromOutboundTag() {
        assertThat(LiveVia.of("direct", "direct")).isEqualTo(LiveVia.DIRECT)
        assertThat(LiveVia.of("proxy", "vless")).isEqualTo(LiveVia.PROXY)
        assertThat(LiveVia.of("pipe-video", "vless")).isEqualTo(LiveVia.PROXY)
        assertThat(LiveVia.of("block", "block")).isEqualTo(LiveVia.OTHER)
    }
}
