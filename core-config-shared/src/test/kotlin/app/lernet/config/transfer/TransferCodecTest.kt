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

    @Test fun `round trip preserves disconnected branches and their children`() {
        val loose = sample.rules.single().copy(parentId = "orphan")
        val child = loose.copy(id = "child", parentId = loose.id)
        val bundle = sample.copy(rules = listOf(loose, child))
        assertEquals(bundle, TransferCodec.decode(TransferCodec.encode(bundle)))
    }

    @Test fun `reserved ids and ambiguous owners are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(sample.copy(rules = listOf(sample.rules.single().copy(id = "orphan"))))
        }
        val profile = sample.profiles.single().copy(id = "grp_folder")
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(sample.copy(profiles = listOf(profile),
                groups = listOf(sample.groups.single().copy(profileIds = listOf(profile.id)))))
        }
    }

    @Test fun `rejects broken conditions and unknown mode without rewriting the archive`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(sample.copy(rules = listOf(sample.rules.single().copy(blocksJson = "{broken"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TransferCodec.encode(sample.copy(profiles = listOf(sample.profiles.single().copy(modeOverride = "VPN"))))
        }
    }

    @Test fun `owner remapping uses references instead of guessing from a prefix`() {
        val owners = TransferCodec.remapOwnerIds(mapOf("grp_standalone" to "p-new"), mapOf("folder" to "g-new"))
        assertEquals("p-new", owners["grp_standalone"])
        assertEquals("grp_g-new", owners["grp_folder"])
        assertEquals(null, TransferCodec.remapParentId(null, emptyMap()))
        assertEquals("orphan", TransferCodec.remapParentId("orphan", emptyMap()))
        assertEquals("orphan", TransferCodec.remapParentId("missing", emptyMap()))
        assertEquals("new-rule", TransferCodec.remapParentId("rule", mapOf("rule" to "new-rule")))
    }
}
