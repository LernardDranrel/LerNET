package app.lernet.ui.diag

import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveConnStatus
import app.lernet.engine.live.LiveVia
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagSnapshotTest {
    @Test
    fun tlsRowsExplainHiddenPayloadAndKeepDest() {
        val model = DiagSnapshot.of(
            row(
                protocol = "tls",
                domain = "video.twimg.com",
                destHost = "212.80.221.99",
                destPort = 443,
            ),
        )
        assertThat(model.dest).isEqualTo("212.80.221.99:443")
        assertThat(model.domain).isEqualTo("video.twimg.com")
        assertThat(model.wire).isEqualTo(DiagSnapshot.Wire.TLS)
        assertThat(model.hasCapturedContent).isFalse()
    }

    @Test
    fun cleartextShowsCapturedHeadersAndNotesMissingBody() {
        val model = DiagSnapshot.of(
            row(
                protocol = "http/1.1",
                method = "GET /",
                headers = "Host: example.com",
                body = null,
            ),
        )
        assertThat(model.wire).isEqualTo(DiagSnapshot.Wire.CLEARTEXT)
        assertThat(model.requestLine).isEqualTo("GET /")
        assertThat(model.headers).isEqualTo("Host: example.com")
        assertThat(model.hasCapturedContent).isTrue()
    }

    @Test
    fun unknownWireWithoutCaptureStaysAbsentNotTls() {
        val model = DiagSnapshot.of(row(protocol = null))
        assertThat(model.wire).isEqualTo(DiagSnapshot.Wire.UNKNOWN)
        assertThat(model.domain).isNull()
        assertThat(model.hasCapturedContent).isFalse()
    }

    @Test
    fun largeBodyIsCollapsedByUiAndBoundedForRendering() {
        val model = DiagSnapshot.of(row(protocol = "http", body = "x".repeat(20_000)))
        assertThat(model.hasCapturedContent).isTrue()
        assertThat(model.bodyTruncated).isTrue()
        assertThat(model.body?.length).isAtMost(16_385)
    }

    @Test
    fun transportAndOutboundChainRemainVisibleWithoutSniffedProtocol() {
        val model = DiagSnapshot.of(
            row(protocol = null).copy(
                transport = "tcp",
                ipVersion = 4,
                routeChain = listOf("selector", "pipe-main"),
            ),
        )
        assertThat(model.transport).isEqualTo("TCP")
        assertThat(model.ipVersion).isEqualTo(4)
        assertThat(model.routeChain).containsExactly("selector", "pipe-main").inOrder()
        assertThat(model.protocol).isNull()
    }

    private fun row(
        protocol: String?,
        domain: String? = null,
        destHost: String = "203.0.113.10",
        destPort: Int = 443,
        method: String? = null,
        headers: String? = null,
        body: String? = null,
    ): LiveConn = LiveConn(
        id = "1",
        app = "?",
        uid = 10352,
        destHost = destHost,
        destPort = destPort,
        domain = domain,
        outbound = "proxy",
        via = LiveVia.PROXY,
        uplink = 10,
        downlink = 20,
        rule = "правило",
        status = LiveConnStatus.CLOSED,
        method = method,
        protocol = protocol,
        headers = headers,
        body = body,
    )
}
