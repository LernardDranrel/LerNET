package app.lernet.engine.live

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProxiedRepliesTest {
    private fun row(id: String, via: LiveVia, down: Long, status: LiveConnStatus = LiveConnStatus.OPEN) = LiveConn(
        id = id, app = "app", uid = null, destHost = "h", destPort = 443, domain = null,
        outbound = if (via == LiveVia.DIRECT) "direct" else "proxy", via = via,
        uplink = 100, downlink = down, status = status,
    )

    @Test
    fun onlyRepliesThroughTheExitCount() {
        val replies = ProxiedReplies()
        assertThat(replies.observe(false, listOf(row("d1", LiveVia.DIRECT, 5_000)))).isFalse()
        assertThat(replies.observe(false, listOf(row("d1", LiveVia.DIRECT, 9_000)))).isFalse()
        assertThat(replies.total).isEqualTo(0)
        assertThat(replies.observe(false, listOf(row("p1", LiveVia.PROXY, 0)))).isFalse()
        assertThat(replies.observe(false, listOf(row("p1", LiveVia.PROXY, 1_200)))).isTrue()
        assertThat(replies.observe(false, listOf(row("p1", LiveVia.PROXY, 1_200)))).isFalse()
        assertThat(replies.total).isEqualTo(1)
    }

    @Test
    fun closedRowsAreForgottenAndResetKeepsOnlyLiveOnes() {
        val replies = ProxiedReplies()
        replies.observe(false, listOf(row("p1", LiveVia.PROXY, 10), row("p2", LiveVia.PROXY, 10)))
        replies.observe(false, listOf(row("p1", LiveVia.PROXY, 10, LiveConnStatus.CLOSED)))
        // p2 unchanged after a reset snapshot: no new reply.
        assertThat(replies.observe(true, listOf(row("p2", LiveVia.PROXY, 10)))).isFalse()
        assertThat(replies.total).isEqualTo(2)
    }
}
