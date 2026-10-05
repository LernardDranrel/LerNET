package app.lernet.config.policy

import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferOutbound
import app.lernet.config.transfer.TransferProfile
import app.lernet.config.transfer.TransferRule
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import app.lernet.routing.policy.ProfileExitPolicy
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyWorkspaceRepositoryTest {
    private fun profile(id: String) = TransferProfile(
        id, id, "JSON",
        listOf(
            TransferOutbound(
                "$id-out", "proxy", "socks",
                """
        {"type":"socks","server":"$id.example.invalid","password":"test-private-value"}
                """.trimIndent()
            )
        ),
        "$id-out",
    )
    private val legacy = TransferBundle(scope = "all", groups = emptyList(), profiles = listOf(profile("de")), rules = emptyList())
    private fun withRepository(block: (PolicyWorkspaceRepository, PolicyWorkspaceStore) -> Unit) {
        val directory = Files.createTempDirectory("lernet-repository-")
        try {
            val store = PolicyWorkspaceStore(directory.resolve("workspace.json"))
            var counter = 0
            block(PolicyWorkspaceRepository(store) { "local-${++counter}" }, store)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    @Test
    fun `invalid draft remains durable but cannot replace saved policy`() = withRepository { repository, store ->
        val original = repository.open(legacy)
        val invalid = original.saved.copy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("missing", target = PolicyTarget.Profile("absent")))
            )
        )
        repository.updateDraft(invalid)
        assertThrows(IllegalArgumentException::class.java) { repository.saveDraft() }
        val restarted = PolicyWorkspaceRepository(store).open(legacy)
        assertEquals(original.saved, restarted.saved)
        assertEquals(invalid, restarted.draft)
        assertEquals(original.saved, repository.discardDraft().draft)
    }

    @Test
    fun `runtime persistence preserves assigned revision without double increment`() = withRepository { repository, _ ->
        val original = repository.open(legacy)
        val saved = original.saved.copy(revision = 17)
        assertEquals(17L, repository.persist(saved, saved).saved.revision)
        repository.updateDraft(saved.copy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block)))
        assertEquals(18L, repository.saveDraft().saved.revision)
    }

    @Test
    fun `health edits survive restart and inventory reconciliation without silently applying an invalid draft`() =
        withRepository { repository, store ->
            val original = repository.open(legacy)
            val health = PolicyHealthSettings(2_000, 5_000, 3_000, 3)
            repository.updateDraft(original.draft.copy(health = health))
            assertEquals(original.saved.health, repository.snapshot().saved.health)
            val saved = repository.saveDraft()
            assertEquals(health, PolicyWorkspaceRepository(store).open(legacy).saved.health)
            val changedInventory = legacy.copy(profiles = legacy.profiles + profile("nl"))
            assertEquals(health, repository.reconcile(changedInventory).saved.health)
            val invalidHealth = health.copy(activeTimeoutMs = 99_000)
            repository.updateDraft(repository.snapshot().draft.copy(health = invalidHealth))
            assertThrows(IllegalArgumentException::class.java) { repository.saveDraft() }
            val restarted = PolicyWorkspaceRepository(store).open(changedInventory)
            assertEquals(saved.saved.health, restarted.saved.health)
            assertEquals(invalidHealth, restarted.draft.health)
            assertEquals(health, repository.discardDraft().draft.health)
        }

    @Test
    fun `removed profile blocks its device references and records explanation`() = withRepository { repository, _ ->
        val original = repository.open(legacy)
        val saved = original.saved.copy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("guard", title = "Private", target = PolicyTarget.Profile("de")))
            )
        )
        repository.persist(saved, saved)
        val changed = repository.reconcile(legacy.copy(profiles = emptyList()))
        assertEquals(PolicyTarget.Block, changed.saved.device.nodes.single().target)
        assertTrue(repository.lastNotes.single().contains("Private"))
        assertTrue(changed.saved.trees.isEmpty())
    }

    @Test
    fun `new inventory adds trees while preserving custom saved and dirty draft`() = withRepository { repository, _ ->
        val original = repository.open(legacy)
        val custom = original.saved.copy(device = PolicyTree(PolicyScope.Device, listOf(PolicyNode("custom", target = PolicyTarget.Block))))
        val draft = custom.copy(
            device = custom.device.copy(
                nodes = custom.device.nodes + PolicyNode("draft", target = PolicyTarget.Direct)
            )
        )
        repository.persist(custom, draft)
        val changed = repository.reconcile(legacy.copy(profiles = legacy.profiles + profile("nl")))
        assertEquals(custom.device, changed.saved.device)
        assertEquals(draft.device, changed.draft.device)
        assertEquals(setOf(PolicyScope.Profile("de"), PolicyScope.Profile("nl")), changed.saved.trees.map { it.scope }.toSet())
    }

    @Test
    fun `append import remaps once and journal commits exact legacy IDs`() = withRepository { repository, store ->
        repository.open(legacy)
        val imported = PolicyMigration.migrate(legacy).let { workspace ->
            val saved = workspace.saved.copy(
                device = PolicyTree(
                    PolicyScope.Device,
                    listOf(PolicyNode("imported", target = PolicyTarget.Profile("de")))
                )
            )
            workspace.copy(saved = saved, draft = saved)
        }
        val plan = repository.previewImported(PolicyWorkspaceCodec.encode(imported))
        assertNotEquals("de", plan.after.legacy.profiles.last().id)
        assertEquals(plan.after.legacy.profiles.last().id, (plan.after.saved.device.nodes.single().target as PolicyTarget.Profile).id)
        var received: TransferBundle? = null
        val committed = repository.commitImported(plan) { received = it }
        assertEquals(plan.after.legacy, received)
        assertEquals(committed, store.load())
        assertEquals(null, store.loadPendingImport())
        assertTrue(repository.export().contains("test-private-value"))
    }

    @Test
    fun `interrupted cross store import recovers forward after legacy transaction`() = withRepository { repository, store ->
        repository.open(legacy)
        val plan = repository.previewImported(TransferCodec.encode(legacy))
        store.savePendingImport(plan)
        val restored = PolicyWorkspaceRepository(store).open(plan.after.legacy)
        assertEquals(plan.after, restored)
        assertEquals(null, store.loadPendingImport())
    }

    @Test
    fun `failed legacy transaction recovers previous workspace and preserves profile count`() = withRepository { repository, store ->
        val before = repository.open(legacy)
        val plan = repository.previewImported(TransferCodec.encode(legacy))
        assertThrows(IllegalStateException::class.java) { repository.commitImported(plan) { error("atomic transaction failed") } }
        assertEquals(before, repository.snapshot())
        assertEquals(before, PolicyWorkspaceRepository(store).open(legacy))
        assertEquals(null, store.loadPendingImport())
    }

    @Test
    fun `legacy named structural parent migrates without becoming a physical destination`() {
        val rules = listOf(
            TransferRule("parent", "de", sortIndex = 0, action = "PROXY", pipeName = "parent name", domains = listOf("*.example")),
            TransferRule("child", "de", parentId = "parent", sortIndex = 0, action = "BLOCK", domains = listOf("one.example")),
            TransferRule("otherwise", "de", parentId = "parent", sortIndex = 1, action = "PROXY"),
        )
        val original = legacy.copy(rules = rules)
        val migrated = PolicyWorkspaceCodec.decode(TransferCodec.encode(original))
        assertEquals(original, migrated.legacy)
        assertEquals(PolicyTarget.CurrentExit, migrated.saved.trees.single().nodes.first().target)
        assertFalse(PolicyWorkspaceCodec.encode(migrated).isBlank())
    }

    @Test
    fun `profile lifecycle survives remapped import and is pruned when profile disappears`() = withRepository { repository, _ ->
        val migrated = repository.open(legacy)
        val life = ExitLifecyclePolicy(coldStart = true)
        val policy = migrated.saved.copy(profilePolicies = listOf(ProfileExitPolicy("de", life)))
        repository.persist(policy, policy)
        val imported = repository.previewImported(repository.export())
        val importedProfile = imported.after.legacy.profiles.last().id
        assertEquals(life, imported.after.saved.profilePolicies.single { it.profileId == importedProfile }.lifecycle)
        val changed = repository.reconcile(legacy.copy(profiles = emptyList()))
        assertTrue(changed.saved.profilePolicies.isEmpty())
    }

    @Test
    fun `portable invalid draft keeps missing destination isolated instead of binding a local profile`() = withRepository { repository, _ ->
        val original = repository.open(legacy)
        val draft = original.draft.copy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("dirty", target = PolicyTarget.Profile("missing")))
            )
        )
        val imported = original.copy(draft = draft)
        val plan = repository.previewImported(PolicyWorkspaceCodec.encode(imported))
        val missing = (plan.after.draft.device.nodes.single().target as PolicyTarget.Profile).id
        assertNotEquals("missing", missing)
        assertFalse(missing in plan.after.legacy.profiles.map { it.id })
        assertTrue(plan.after.saved.device.nodes.isEmpty())
    }

    @Test
    fun `legacy node root and named channel coordinates survive workspace archive and remapped import`() = withRepository { repository, _ ->
        val layout = """{"node-root":{"x":10,"y":20},"rule:r":{"x":30,"y":40},"pipe:isolated":{"x":50,"y":60}}"""
        val source = legacy.copy(
            profiles = listOf(profile("de").copy(canvasLayout = layout)),
            rules = listOf(TransferRule("r", "de", sortIndex = 0, action = "PROXY", pipeName = "isolated"))
        )
        val migrated = repository.open(source)
        val channel = migrated.saved.channels.single().id
        val positions = migrated.saved.trees.single().positions
        assertEquals(PolicyCanvasPoint(10f, 20f), positions["root"])
        assertEquals(PolicyCanvasPoint(30f, 40f), positions["r"])
        assertEquals(PolicyCanvasPoint(50f, 60f), positions["channel:$channel"])
        assertEquals(migrated, PolicyWorkspaceCodec.decode(repository.export()))
        val plan = repository.previewImported(repository.export())
        val copiedTree = plan.after.saved.trees.last()
        val copiedChannel = plan.after.saved.channels.last().id
        assertEquals(positions["r"], copiedTree.positions[copiedTree.nodes.single().id])
        assertEquals(positions["channel:$channel"], copiedTree.positions["channel:$copiedChannel"])
    }

    @Test
    fun `manual canvas movement does not freeze migrated rules and deleted nodes lose coordinates`() = withRepository { repository, _ ->
        val rule = TransferRule("r", "de", sortIndex = 0, action = "BLOCK", domains = listOf("one.example"))
        val source = legacy.copy(rules = listOf(rule))
        val original = repository.open(source)
        val moved = original.saved.copy(
            trees = listOf(
                original.saved.trees.single().copy(
                    positions = mapOf("r" to PolicyCanvasPoint(999f, 888f))
                )
            )
        )
        repository.persist(moved, moved)
        val refreshed = repository.reconcile(source.copy(rules = listOf(rule.copy(title = "Updated", domains = listOf("two.example")))))
        assertEquals("Updated", refreshed.saved.trees.single().nodes.single().title)
        assertEquals(PolicyCanvasPoint(999f, 888f), refreshed.saved.trees.single().positions["r"])
        val deleted = repository.reconcile(source.copy(rules = emptyList()))
        assertTrue(deleted.saved.trees.single().positions.isEmpty())
    }

    @Test
    fun `legacy rule named root retains its coordinates independently of canvas root`() {
        val source = legacy.copy(
            profiles = listOf(
                profile("de").copy(
                    canvasLayout =
                    """{"node-root":{"x":10,"y":20},"rule:root":{"x":30,"y":40}}"""
                )
            ),
            rules = listOf(TransferRule("root", "de", sortIndex = 0, action = "BLOCK"))
        )
        val migrated = PolicyMigration.migrate(source)
        assertEquals(PolicyCanvasPoint(10f, 20f), migrated.saved.trees.single().positions["root"])
        assertEquals(PolicyCanvasPoint(30f, 40f), migrated.saved.trees.single().positions["node:root"])
        assertEquals(migrated, PolicyWorkspaceCodec.decode(PolicyWorkspaceCodec.encode(migrated)))
    }

    @Test
    fun `append import keeps remapped device coordinates and preserves existing shared root`() = withRepository { repository, _ ->
        val original = repository.open(legacy)
        val existing = original.saved.copy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(PolicyNode("existing", target = PolicyTarget.Block)),
                positions = mapOf(
                    "existing" to PolicyCanvasPoint(10f, 20f), PolicyCanvasKeys.ROOT to PolicyCanvasPoint(30f, 40f),
                )
            )
        )
        repository.persist(existing, existing)
        val imported = PolicyMigration.migrate(legacy).let { workspace ->
            val saved = workspace.saved.copy(
                device = PolicyTree(
                    PolicyScope.Device, listOf(PolicyNode("incoming", target = PolicyTarget.Channel("shared"))),
                    positions = mapOf(
                        "incoming" to PolicyCanvasPoint(50f, 60f), "channel:shared" to PolicyCanvasPoint(70f, 80f),
                        PolicyCanvasKeys.ROOT to PolicyCanvasPoint(90f, 100f)
                    )
                ),
                channels = listOf(PolicyChannel("shared", "Imported", PolicyScope.Device, PolicyTarget.Profile("de"))),
            )
            workspace.copy(saved = saved, draft = saved)
        }
        val after = repository.previewImported(PolicyWorkspaceCodec.encode(imported)).after
        val importedNode = after.saved.device.nodes.last()
        val importedChannel = after.saved.channels.last()
        assertEquals(4, after.saved.device.positions.size)
        assertEquals(PolicyCanvasPoint(30f, 40f), after.saved.device.positions[PolicyCanvasKeys.ROOT])
        assertEquals(PolicyCanvasPoint(50f, 60f), after.saved.device.positions[PolicyCanvasKeys.node(importedNode.id)])
        assertEquals(PolicyCanvasPoint(70f, 80f), after.saved.device.positions[PolicyCanvasKeys.channel(importedChannel.id)])
        assertEquals(after.saved.device.positions, after.draft.device.positions)
    }

    @Test
    fun `simple profile selection leaves Expert revisions unchanged`() = withRepository { repository, store ->
        val before = repository.open(legacy)
        val changed = repository.reconcile(legacy.copy(selectedProfileId = "de"))
        assertEquals(before.saved, changed.saved)
        assertEquals(before.draft, changed.draft)
        assertEquals("de", changed.legacy.selectedProfileId)
        assertEquals(changed, store.load())
        val replaced = repository.reconcile(
            changed.legacy.copy(
                profiles = changed.legacy.profiles.map { profile ->
                    profile.copy(
                        outbounds = profile.outbounds.map {
                            it.copy(singBoxJson = it.singBoxJson.replace("test-private-value", "new-private-value"))
                        }
                    )
                }
            )
        )
        assertTrue(replaced.saved.revision > changed.saved.revision)
    }

    @Test
    fun `inventory preview is side effect free until journal commits exact platform inventory`() = withRepository { repository, store ->
        val before = repository.open(legacy)
        val bundle = before.legacy.copy(profiles = before.legacy.profiles + profile("nl"))
        val plan = repository.previewInventory(bundle)
        assertEquals(before, repository.snapshot())
        assertEquals(before, store.load())
        assertEquals(bundle, plan.after.legacy)
        var committed: TransferBundle? = null
        val after = repository.commitImported(plan) { committed = it }
        assertEquals(bundle, committed)
        assertEquals(after, store.load())
    }
}
