package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferGroup
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferRule
import app.lernet.routing.MatchJoin
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyMigrationTest {
    private val outboundJson = "{\"type\":\"vless\",\"server\":\"example.org\",\"uuid\":\"test-secret\"}"
    private val legacy = TransferBundle(
        scope = "all",
        groups = listOf(TransferGroup("folder", "Base", listOf("de"), autoSwap = true)),
        profiles = listOf(
            TransferProfile(
                "de", "Germany", "JSON",
                listOf(TransferOutbound("o", "proxy", "vless", outboundJson)), "o",
                canvasLayout = "{\"rule:r\":{\"x\":1,\"y\":2}}",
            ),
        ),
        rules = listOf(
            TransferRule(
                "r", "grp_folder", sortIndex = 0, action = "PROXY",
                pipeName = "Telegram", domains = listOf("one.example"),
            ),
            TransferRule(
                "s", "grp_folder", sortIndex = 1, action = "PROXY",
                pipeName = "Telegram", apps = listOf("org.telegram.messenger"),
            ),
            TransferRule(
                "detached", "de", parentId = TransferCodec.ORPHAN_PARENT,
                sortIndex = 0, action = "BLOCK",
            ),
        ),
        selectedProfileId = "de",
    )

    @Test fun `migration preserves credentials layouts platform branches and source archive`() {
        val raw = TransferCodec.encode(legacy)
        val migrated = PolicyWorkspaceCodec.decode(raw)
        assertEquals(legacy, migrated.legacy)
        assertEquals(raw, TransferCodec.encode(migrated.legacy))
        assertEquals(migrated, PolicyWorkspaceCodec.decode(PolicyWorkspaceCodec.encode(migrated)))
        assertEquals(PolicyScope.Folder("folder"), PolicyMigration.effectiveScope(legacy, "de"))
        assertEquals(true, migrated.saved.folderPolicies.single().autoSwap)
        assertEquals(1, migrated.saved.channels.size)
        assertEquals(2, migrated.saved.trees.first { it.scope is PolicyScope.Folder }.nodes.size)
        assertTrue(migrated.saved.trees.first { it.scope is PolicyScope.Profile }.nodes.single().detached)
        assertEquals(PolicyTarget.Direct, migrated.saved.device.defaultTarget)
    }

    @Test fun `health settings survive archive round trip and older workspaces receive safe defaults`() {
        val health = PolicyHealthSettings(2_000, 5_000, 3_000, 3)
        val migrated = PolicyMigration.migrate(legacy)
        val configured = migrated.copy(
            saved = migrated.saved.copy(health = health),
            draft = migrated.draft.copy(health = health.copy(activeTimeoutMs = 5_000))
        )
        assertEquals(configured, PolicyWorkspaceCodec.decode(PolicyWorkspaceCodec.encode(configured)))
        val oldHeader = Json.parseToJsonElement(PolicyWorkspaceCodec.encode(configured)).jsonObject
        val oldArchive = JsonObject(
            oldHeader + listOf("saved", "draft").associateWith { field ->
                JsonObject(oldHeader.getValue(field).jsonObject.filterKeys { it != "health" })
            }
        )
        val restored = PolicyWorkspaceCodec.decode(oldArchive.toString())
        assertEquals(PolicyHealthSettings(), restored.saved.health)
        assertEquals(PolicyHealthSettings(), restored.draft.health)
        assertEquals(PolicyHealthSettings(), PolicyMigration.migrate(legacy).saved.health)
        assertThrows(IllegalArgumentException::class.java) {
            val invalid = configured.saved.copy(health = health.copy(maximumIntervalMs = 1_000))
            PolicyWorkspaceCodec.encode(configured.copy(saved = invalid))
        }
    }

    @Test fun `folder without its own rules preserves profile inheritance`() {
        val noFolderRules = legacy.copy(rules = legacy.rules.filter { it.ownerId == "de" })
        assertEquals(PolicyScope.Profile("de"), PolicyMigration.effectiveScope(noFolderRules, "de"))
    }

    @Test fun `channel identity is stable during migration and separate per owner`() {
        val extra = legacy.rules.first().copy(id = "other", ownerId = "de", parentId = null)
        val archive = legacy.copy(rules = legacy.rules + extra)
        val first = PolicyMigration.migrate(archive)
        val second = PolicyMigration.migrate(archive)
        assertEquals(first.saved.channels, second.saved.channels)
        assertEquals(2, first.saved.channels.map { it.id }.distinct().size)
    }

    @Test fun `flat desktop OR join survives migration and structured join remains authoritative`() {
        val flat = legacy.rules.first().copy(domains = listOf("one.example"), processes = listOf("Telegram.exe"), join = "OR")
        val archive = legacy.copy(rules = listOf(flat))
        val migrated = PolicyMigration.migrate(archive).saved.trees.first { it.scope is PolicyScope.Folder }.nodes.single()
        assertEquals(MatchJoin.OR, migrated.conditions.join)
        assertEquals(2, migrated.conditions.blocks.size)
        val structuredJson = "{\"join\":\"AND\",\"blocks\":[{\"kind\":\"DOMAIN\",\"values\":[\"one.example\"]}]}"
        val structured = flat.copy(blocksJson = structuredJson)
        val result = PolicyMigration.migrate(archive.copy(rules = listOf(structured)))
        assertEquals(MatchJoin.AND, result.saved.trees.first { it.scope is PolicyScope.Folder }.nodes.single().conditions.join)
    }

    @Test fun `workspace preserves invalid draft but rejects invalid saved and future version`() {
        val workspace = PolicyMigration.migrate(legacy)
        val invalidNodes = listOf(PolicyNode("x", target = PolicyTarget.Profile("absent")))
        val invalid = NetworkPolicy(device = PolicyTree(PolicyScope.Device, invalidNodes))
        val draft = workspace.copy(draft = invalid)
        assertEquals(draft, PolicyWorkspaceCodec.decode(PolicyWorkspaceCodec.encode(draft)))
        assertFalse(PolicyProgramCompiler.compile(draft.draft, PolicyMigration.inventory(legacy), RoutePlatform.WINDOWS).isValid)
        assertThrows(IllegalArgumentException::class.java) { PolicyWorkspaceCodec.encode(workspace.copy(saved = invalid)) }
        assertThrows(IllegalArgumentException::class.java) { PolicyWorkspaceCodec.encode(workspace.copy(version = 99)) }
    }

    @Test fun `corrupt primary recovers from backup without overwriting it on next save`() {
        val directory = Files.createTempDirectory("lernet-policy-test-")
        try {
            val file = directory.resolve("workspace.json")
            val store = PolicyWorkspaceStore(file)
            val initial = PolicyMigration.migrate(legacy)
            store.save(initial)
            val updated = initial.copy(saved = initial.saved.copy(revision = 1))
            store.save(updated)
            Files.write(file, "broken".toByteArray())
            assertEquals(initial, store.load())
            store.save(updated)
            val backupRaw = Files.readAllBytes(directory.resolve("workspace.json.bak")).toString(Charsets.UTF_8)
            assertEquals(initial, PolicyWorkspaceCodec.decode(backupRaw))
            assertEquals(updated, store.load())
        } finally {
            Files.list(directory).use { files -> files.forEach(Files::delete) }
            Files.delete(directory)
        }
    }

    @Test fun `future primary cannot be replaced with stale backup by older client`() {
        val directory = Files.createTempDirectory("lernet-policy-future-test-")
        try {
            val file = directory.resolve("workspace.json")
            val store = PolicyWorkspaceStore(file)
            val initial = PolicyMigration.migrate(legacy)
            store.save(initial)
            store.save(initial)
            val future = PolicyWorkspaceCodec.encode(initial).replace("\"version\": 1", "\"version\": 99")
            Files.write(file, future.toByteArray(Charsets.UTF_8))
            assertThrows(UnsupportedPolicyVersion::class.java) { store.load() }
            assertThrows(UnsupportedPolicyVersion::class.java) { store.save(initial) }
            assertEquals(future, Files.readAllBytes(file).toString(Charsets.UTF_8))
        } finally {
            Files.list(directory).use { files -> files.forEach(Files::delete) }
            Files.delete(directory)
        }
    }
}
