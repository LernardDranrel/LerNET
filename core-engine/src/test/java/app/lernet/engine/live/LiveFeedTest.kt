package app.lernet.engine.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveFeedTest {
    @Test
    fun resetOrdersNativeRowsByCreationAndKeepsLatest() {
        val feed = LiveFeed(maxRows = 2)
        feed.apply(reset = true, incoming = listOf(
            sample("new").copy(createdAt = 300),
            sample("old").copy(createdAt = 100),
            sample("middle").copy(createdAt = 200),
        ))
        assertThat(feed.rows.value.map { it.id }).containsExactly("middle", "new").inOrder()
    }

    @Test
    fun liveRowsUpdateWithoutRecordingExportStaysEmpty() {
        val feed = LiveFeed()
        feed.apply(reset = true, incoming = listOf(sample("a")))
        assertThat(feed.rows.value).hasSize(1)
        assertThat(feed.liveText()).contains("a.example")
        assertThat(feed.recordedText()).isEmpty()
    }

    @Test
    fun recordingToggleCapturesOnlyWhileOn() {
        val feed = LiveFeed()
        feed.apply(reset = false, incoming = listOf(sample("before")))
        feed.setRecording(true)
        feed.apply(reset = false, incoming = listOf(sample("on")))
        feed.setRecording(false)
        feed.apply(reset = false, incoming = listOf(sample("after")))
        assertThat(feed.recordedText()).contains("on.example")
        assertThat(feed.recordedText()).doesNotContain("before.example")
        assertThat(feed.recordedText()).doesNotContain("after.example")
        assertThat(feed.liveText()).contains("after.example")
    }

    @Test
    fun recordingRedactsUuidBeforeDisk() {
        val feed = LiveFeed()
        feed.setRecording(true)
        feed.apply(
            reset = false,
            incoming = listOf(
                sample("row").copy(app = "11111111-1111-1111-1111-111111111111"),
            ),
        )
        assertThat(feed.recordedText()).doesNotContain("11111111-1111-1111-1111-111111111111")
        assertThat(sample("plain").method).isNull()
        assertThat(sample("plain").headers).isNull()
        assertThat(sample("plain").body).isNull()
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
