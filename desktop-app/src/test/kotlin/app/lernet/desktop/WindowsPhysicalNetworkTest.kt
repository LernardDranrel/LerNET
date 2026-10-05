package app.lernet.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WindowsPhysicalNetworkTest {
    @Test
    fun `physical network preference uses combined route metric then interface index`() {
        val selected = WindowsPhysicalNetwork.choose("""[
            {"name":"Ethernet","index":8,"metric":50},
            {"name":"Wi-Fi","index":10,"metric":20},
            {"name":"Ethernet 2","index":7,"metric":20}
        ]""")
        assertEquals(PhysicalNetwork("Ethernet 2", 7, 20), selected)
    }

    @Test
    fun `missing or malformed physical identity cannot become an underlay`() {
        listOf("[]", "{}", """[{"name":"\n","index":1,"metric":20}]""", """[{"name":"Wi-Fi","index":0,"metric":20}]""").forEach { raw ->
            assertThrows(IllegalStateException::class.java) { WindowsPhysicalNetwork.choose(raw) }
        }
    }
}
