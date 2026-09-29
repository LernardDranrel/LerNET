package app.lernet.desktop

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferRule
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionCodec
import app.lernet.routing.ConditionKind
import app.lernet.routing.RuleConditions
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** These tests reject the config before launching any executable or touching the device network. */
class DesktopConnectionTest {
    @Test fun copyingProfileNeverPromotesDisconnectedRulesToRoot() {
        val store = DesktopStore(Files.createTempDirectory("lernet-copy-orphans"))
        val imported = DesktopTransfer.merge(StoredState(), androidArchive())
        val profile = imported.profiles.single()
        val loose = imported.rules.single().copy(profileId = profile.id, parentId = "orphan")
        val child = loose.copy(id = "child", parentId = loose.id)
        store.save(imported.copy(rules = listOf(loose, child)))
        val controller = DesktopController(store)
        try {
            controller.duplicateProfile(profile.id)
            val saved = store.load()
            val copy = saved.profiles.single { it.id != profile.id }
            val copied = saved.rules.filter { it.profileId == copy.id }
            val parent = copied.single { it.parentId == "orphan" }
            assertEquals(parent.id, copied.single { it.id != parent.id }.parentId)
            assertEquals(profile.groupId, copy.groupId)
        } finally {
            controller.close()
        }
    }

    private fun androidArchive(structured: Boolean = true): String = TransferCodec.encode(TransferBundle(
        scope = "group",
        groups = listOf(TransferGroup("g", "Imported folder", listOf("p"))),
        profiles = listOf(TransferProfile("p", "Imported profile", "test",
            listOf(TransferOutbound("o", "proxy", "direct", """{"type":"direct","tag":"proxy"}""")), "o")),
        rules = listOf(TransferRule("app", "grp_g", sortIndex = 0, action = "proxy", title = "Telegram",
            apps = listOf("org.example.messenger"),
            blocksJson = if (structured) ConditionCodec.encode(RuleConditions(blocks = listOf(
                ConditionBlock(ConditionKind.APP, listOf("org.example.messenger"))
            ))) else "")),
    ))

    @Test fun invalidActiveRuleShowsStartupErrorAndWritesJournal() {
        val directory = Files.createTempDirectory("lernet-import-connection")
        val store = DesktopStore(directory)
        // Even if validation regresses, this path cannot start a real core.
        val imported = DesktopTransfer.merge(StoredState(corePath = directory.resolve("missing.exe").toString()), androidArchive())
        val saved = imported.copy(rules = imported.rules.map { rule -> rule.copy(apps = emptyList(), domains = listOf("invalid domain"),
            blocksJson = ConditionCodec.encode(RuleConditions(blocks = listOf(
                ConditionBlock(ConditionKind.DOMAIN, listOf("invalid domain"))
            )))) })
        store.save(saved)
        val controller = DesktopController(store)
        try {
            controller.connect()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (controller.state.value.busy && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(controller.state.value.busy)
            val failure = controller.state.value.connectionError
            assertNotNull(failure)
            assertTrue(failure!!.message, failure.message.contains("domain"))
            assertEquals("grp_${saved.groups.single().id}", failure.routeOwnerId)
            assertEquals(TunnelStatus.STOPPED, controller.tunnel.state.value.status)
            assertTrue(Files.readString(directory.resolve("session.log")).contains(failure.message))
            assertFalse(Files.exists(directory.resolve("active.json")))
            assertEquals(saved.rules, store.load().rules)
            controller.dismissConnectionError()
            assertNull(controller.state.value.connectionError)
        } finally {
            controller.close()
        }
    }

    @Test fun legacyAppRuleIsInactiveAndCannotTurnIntoCatchAll() {
        val store = DesktopStore(Files.createTempDirectory("lernet-legacy-app-rule"))
        store.save(DesktopTransfer.merge(StoredState(), androidArchive(structured = false)))
        val controller = DesktopController(store)
        try {
            val preview = controller.preview()
            assertTrue(preview.isValid)
            assertFalse(preview.json.contains("org.example.messenger"))
            assertTrue(preview.notes.any { it.contains("неактивны") })
            assertEquals(1, DesktopRouteTree.platformInactiveIds(store.load().rules).size)
        } finally {
            controller.close()
        }
    }

    @Test fun importedAndroidBranchStaysPortableWithItsChildren() {
        val store = DesktopStore(Files.createTempDirectory("lernet-portable-app-rule"))
        val imported = DesktopTransfer.merge(StoredState(), androidArchive())
        val root = imported.rules.single()
        val child = root.copy(id = "child", parentId = root.id, title = "Child", apps = emptyList(),
            blocksJson = "", domains = listOf("example.com"))
        val saved = imported.copy(rules = imported.rules + child)
        store.save(saved)
        val controller = DesktopController(store)
        try {
            val preview = controller.preview()
            assertTrue(preview.isValid)
            assertFalse(preview.json.contains("example.com"))
            assertEquals(setOf(root.id, child.id), DesktopRouteTree.platformInactiveIds(saved.rules))
            val exported = TransferCodec.decode(DesktopTransfer.export(store.load(), saved.groups.single().id))
            assertTrue(exported.rules.all { it.enabled })
            assertEquals(root.blocksJson, exported.rules.first { it.id == root.id }.blocksJson)
            assertEquals(root.id, exported.rules.first { it.id == child.id }.parentId)
        } finally {
            controller.close()
        }
    }

    @Test fun disablingIncompatibleRuleAllowsPreviewWithoutDeletingImportedData() {
        val store = DesktopStore(Files.createTempDirectory("lernet-disabled-app-rule"))
        val saved = DesktopTransfer.merge(StoredState(), androidArchive())
        store.save(saved.copy(rules = saved.rules.map { it.copy(enabled = false) }))
        val controller = DesktopController(store)
        try {
            assertTrue(controller.preview().isValid)
            assertEquals(listOf("org.example.messenger"), store.load().rules.single().apps)
        } finally {
            controller.close()
        }
    }

    @Test fun emptyForeignConditionIsStillInactiveInsteadOfBecomingOtherwise() {
        val store = DesktopStore(Files.createTempDirectory("lernet-empty-foreign-condition"))
        val imported = DesktopTransfer.merge(StoredState(), androidArchive())
        val rule = imported.rules.single().copy(apps = emptyList(), blocksJson = ConditionCodec.encode(
            RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.APP)))
        ))
        store.save(imported.copy(rules = listOf(rule)))
        val controller = DesktopController(store)
        try {
            assertFalse(DesktopRouteTree.isElse(rule))
            assertEquals(setOf(rule.id), DesktopRouteTree.platformInactiveIds(listOf(rule)))
            val preview = controller.preview()
            assertTrue(preview.isValid)
            assertTrue(preview.notes.any { it.contains("неактивны") })
        } finally {
            controller.close()
        }
    }
}
