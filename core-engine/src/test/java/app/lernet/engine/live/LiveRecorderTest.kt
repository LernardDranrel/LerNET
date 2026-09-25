package app.lernet.engine.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveRecorderTest {
    @Test
    fun offDoesNotWriteAndOnCapturesExport() {
        val rec = LiveRecorder()
        rec.apply(reset = false, rows = listOf(sample("a")))
        assertThat(rec.exportText()).isEmpty()
        rec.setRecording(true)
        rec.apply(reset = false, rows = listOf(sample("b")))
        assertThat(rec.exportText()).contains("b.example")
        rec.setRecording(false)
        rec.apply(reset = false, rows = listOf(sample("c")))
        assertThat(rec.exportText()).contains("b.example")
        assertThat(rec.exportText()).doesNotContain("c.example")
    }

    private fun sample(id: String): LiveConn =
        LiveConn(
            id = id,
            app = "app.$id",
            uid = 1000,
            destHost = "$id.example",
            destPort = 443,
            domain = null,
            outbound = "proxy",
            via = LiveVia.PROXY,
            uplink = 1,
            downlink = 2,
        )
}
