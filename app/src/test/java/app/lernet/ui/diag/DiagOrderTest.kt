package app.lernet.ui.diag

import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveVia
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagOrderTest {
    @Test
    fun firstPageStartsWithNewestConnection() {
        val chronological = (1..5).map { row(it.toString()) }
        assertEquals(listOf("5", "4"), newestPage(chronological, 0, 2).map { it.id })
        assertEquals(listOf("3", "2"), newestPage(chronological, 1, 2).map { it.id })
        assertEquals(listOf("1"), newestPage(chronological, 2, 2).map { it.id })
    }

    private fun row(id: String) = LiveConn(
        id = id,
        app = "test",
        uid = null,
        destHost = "example.com",
        destPort = 443,
        domain = null,
        outbound = "proxy",
        via = LiveVia.PROXY,
        uplink = 0,
        downlink = 0,
    )
}
