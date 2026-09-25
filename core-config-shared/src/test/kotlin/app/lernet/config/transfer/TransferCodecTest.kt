package app.lernet.config.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferCodecTest {
    private val sample = TransferBundle(
        scope = "group",
        groups = listOf(TransferGroup("folder", "Семья", listOf("profile"), autoSwap = true)),
        profiles = listOf(TransferProfile("profile", "VPN", "VLESS",
            listOf(TransferOutbound("out", "proxy", "vless", "{\"server\":\"example.org\"}")), "out")),
        rules = listOf(TransferRule("rule", "grp_folder", sortIndex = 0, action = "direct", geoip = listOf("ru"))),
    )

    @Test fun `round trip preserves folder routing and settings`() {
        assertEquals(sample, TransferCodec.decode(TransferCodec.encode(sample)))
    }

    @Test fun `rejects missing member and broken rule owner`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(sample.copy(groups = listOf(sample.groups.single().copy(profileIds = listOf("missing")))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(sample.copy(rules = listOf(sample.rules.single().copy(ownerId = "unknown"))))
        }
    }

    @Test fun `rejects unknown schema version before import`() {
        assertThrows(IllegalArgumentException::class.java) { TransferCodec.encode(sample.copy(version = 99)) }
    }

    @Test fun `remaps canvas links to copied rule ids`() {
        val raw = """{"rule:rule":{"x":18,"y":42},"node-root":{"x":0,"y":0}}"""
        val copied = TransferCodec.remapCanvasLayout(raw, mapOf("rule" to "new-rule"))!!
        assertEquals(18f, TransferCodec.pointInLayout(copied, "new-rule")!!.x)
        assertEquals(null, TransferCodec.pointInLayout(copied, "rule"))
    }
}
