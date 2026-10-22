package app.lernet.engine.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveConnTrackerTest {
    private fun row(id: String, up: Long, down: Long) = LiveConn(
        id = id, app = "app", uid = null, destHost = "h", destPort = 443, domain = null,
        outbound = "proxy", via = LiveVia.PROXY, uplink = up, downlink = down,
        status = LiveConnTracker.openStatus(up, down),
    )

    @Test
    fun updatesBecomeRowsWithGrowingBytes() {
        val tracker = LiveConnTracker()
        tracker.onRow(row("c1", 500, 0), closed = false)
        // Sent more, nothing back yet: still unfinished (the 16 KB freeze looks like this).
        val stalled = tracker.onUpdate("c1", 16_000, 0)!!
        assertThat(stalled.uplink).isEqualTo(16_500)
        assertThat(stalled.status).isEqualTo(LiveConnStatus.UNFINISHED)
        val answered = tracker.onUpdate("c1", 0, 2_000)!!
        assertThat(answered.downlink).isEqualTo(2_000)
        assertThat(answered.status).isEqualTo(LiveConnStatus.OPEN)
    }

    @Test
    fun unknownIdsAreIgnoredAndNegativeDeltasDoNotShrink() {
        val tracker = LiveConnTracker()
        assertThat(tracker.onUpdate("nope", 10, 10)).isNull()
        assertThat(tracker.onClosed("nope")).isNull()
        tracker.onRow(row("c1", 100, 100), closed = false)
        val same = tracker.onUpdate("c1", -50, -50)!!
        assertThat(same.uplink).isEqualTo(100)
        assertThat(same.downlink).isEqualTo(100)
    }

    @Test
    fun closedWithoutRowUsesTheLastKnownBytes() {
        val tracker = LiveConnTracker()
        tracker.onRow(row("c1", 100, 0), closed = false)
        tracker.onUpdate("c1", 900, 0)
        val closed = tracker.onClosed("c1")!!
        assertThat(closed.uplink).isEqualTo(1_000)
        // Sent and never answered: reported as unfinished, the block detector's signal.
        assertThat(closed.status).isEqualTo(LiveConnStatus.UNFINISHED)
        assertThat(tracker.onUpdate("c1", 1, 1)).isNull()

        tracker.onRow(row("c2", 100, 50), closed = false)
        assertThat(tracker.onClosed("c2")!!.status).isEqualTo(LiveConnStatus.CLOSED)
    }

    @Test
    fun resetAndCapForgetOldRows() {
        val tracker = LiveConnTracker(maxTracked = 2)
        tracker.onRow(row("a", 1, 1), closed = false)
        tracker.onRow(row("b", 1, 1), closed = false)
        tracker.onRow(row("c", 1, 1), closed = false)
        assertThat(tracker.onUpdate("a", 1, 1)).isNull()
        assertThat(tracker.onUpdate("c", 1, 1)).isNotNull()
        tracker.reset()
        assertThat(tracker.onUpdate("c", 1, 1)).isNull()
    }

    @Test
    fun standbyChecksAreNeverTheConfigsReplies() {
        assertThat(LiveVia.of("standby-1", "vless")).isEqualTo(LiveVia.OTHER)
        assertThat(LiveVia.of("proxy", "vless")).isEqualTo(LiveVia.PROXY)
    }
}
