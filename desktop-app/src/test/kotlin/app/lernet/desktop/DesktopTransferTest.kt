package app.lernet.desktop

import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopTransferTest {
    private val group = StoredGroup("group", "Работа", autoSwap = true)
    private val profile = StoredProfile("profile", "VPN", "VLESS",
        listOf(StoredOutbound("out", "proxy", "vless", "{\"type\":\"vless\"}")), "out", groupId = group.id)
    private val saved = StoredState(
        groups = listOf(group), profiles = listOf(profile), selectedProfileId = profile.id,
        rules = listOf(
            StoredRule("group-rule", "grp_group", sortIndex = 0, domains = listOf("work.example")),
            StoredRule("profile-rule", profile.id, sortIndex = 0, countries = listOf("ru")),
        ),
        rulePositions = mapOf("group-rule" to RulePosition(100f, 200f)),
    )

    @Test fun `folder export includes its settings and both routing trees`() {
        val bundle = TransferCodec.decode(DesktopTransfer.export(saved, group.id))
        assertEquals("group", bundle.scope)
        assertTrue(bundle.groups.single().autoSwap)
        assertEquals(setOf("grp_group", "profile"), bundle.rules.map { it.ownerId }.toSet())
        assertEquals(100f, bundle.rules.first { it.id == "group-rule" }.position!!.x)
    }

    @Test fun `import adds copies without replacing existing profiles or rules`() {
        val archive = DesktopTransfer.export(saved, group.id)
        assertTrue(TransferCodec.isTransfer(archive))
        assertTrue(!TransferCodec.isTransfer("""{"outbounds":[]}"""))
        val merged = DesktopTransfer.merge(saved, archive)
        assertEquals(2, merged.groups.size)
        assertEquals(2, merged.profiles.size)
        assertEquals(4, merged.rules.size)
        assertNotEquals(group.id, merged.groups.last().id)
        assertEquals(merged.groups.last().id, merged.profiles.last().groupId)
        assertTrue(merged.rules.any { it.profileId == "grp_${merged.groups.last().id}" })
        assertEquals(saved.selectedProfileId, merged.selectedProfileId)
    }

    @Test fun `Android folder archive restores routes and canvas positions`() {
        val archive = TransferCodec.encode(TransferBundle(
            scope = "group",
            groups = listOf(TransferGroup("g", "Семья", listOf("p"), true,
                """{"rule:r":{"x":33,"y":44}}""")),
            profiles = listOf(TransferProfile("p", "VPN", "VLESS",
                listOf(TransferOutbound("o", "proxy", "vless", "{\"type\":\"vless\"}")), "o")),
            rules = listOf(TransferRule("r", "grp_g", sortIndex = 0, action = "direct", geoip = listOf("ru"))),
        ))
        val imported = DesktopTransfer.merge(StoredState(), archive)
        val rule = imported.rules.single()
        assertEquals("DIRECT", rule.action)
        assertEquals("grp_${imported.groups.single().id}", rule.profileId)
        assertEquals(RulePosition(33f, 44f), imported.rulePositions[rule.id])
        assertEquals(33f, TransferCodec.pointInLayout(imported.groups.single().canvasLayout, rule.id)!!.x)
    }

    @Test fun `disconnected branches stay disconnected after export and import`() {
        val loose = saved.rules.last().copy(parentId = "orphan")
        val child = loose.copy(id = "child", parentId = loose.id)
        val state = saved.copy(rules = saved.rules.take(1) + loose + child)
        val imported = DesktopTransfer.merge(StoredState(), DesktopTransfer.export(state))
        val rules = imported.rules.filter { it.profileId == imported.profiles.single().id }
        val parent = rules.single { it.parentId == "orphan" }
        assertEquals(parent.id, rules.single { it.id != parent.id }.parentId)
        val exported = TransferCodec.decode(DesktopTransfer.export(imported))
        assertEquals(1, exported.rules.count { it.parentId == "orphan" })
    }

    @Test fun `external profile ids starting with group prefix are still profiles`() {
        val standalone = profile.copy(id = "grp_standalone", groupId = null)
        val state = StoredState(profiles = listOf(standalone), rules = listOf(
            saved.rules.last().copy(profileId = standalone.id)))
        val imported = DesktopTransfer.merge(StoredState(), DesktopTransfer.export(state))
        assertEquals(imported.profiles.single().id, imported.rules.single().profileId)
    }

    @Test fun `workspace transaction preserves already remapped identities and platform preferences`() {
        val preferences = saved.copy(corePath = "C:\\custom\\sing-box.exe", tunMtu = 1400, healthUrl = "https://health.example/204")
        val bundle = TransferCodec.decode(DesktopTransfer.export(saved))
        val materialized = DesktopTransfer.materializeWorkspace(preferences, bundle)
        assertEquals(bundle.profiles.map { it.id }, materialized.profiles.map { it.id })
        assertEquals(bundle.groups.map { it.id }, materialized.groups.map { it.id })
        assertEquals(bundle.rules.map { it.id }, materialized.rules.map { it.id })
        assertEquals(bundle.profiles.single().selectedOutboundId, materialized.profiles.single().selectedOutboundId)
        assertEquals("grp_group", materialized.rules.first().profileId)
        assertEquals(group.id, materialized.profiles.single().groupId)
        assertEquals(preferences.corePath, materialized.corePath)
        assertEquals(preferences.tunMtu, materialized.tunMtu)
        assertEquals(preferences.healthUrl, materialized.healthUrl)
        assertEquals(saved.rulePositions, materialized.rulePositions)
    }

    @Test fun `invalid workspace references fail before materialization`() {
        val bundle = TransferCodec.decode(DesktopTransfer.export(saved))
        val malformed = bundle.copy(groups = bundle.groups.map { it.copy(profileIds = listOf("missing-profile")) })
        val failure = runCatching { DesktopTransfer.materializeWorkspace(saved, malformed) }
        assertTrue(failure.isFailure)
        assertEquals("profile", saved.profiles.single().id)
    }
}
