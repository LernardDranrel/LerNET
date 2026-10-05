package app.lernet.config.policy

import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyContractFixtureTest {
    private fun fixture(name: String): String = requireNotNull(javaClass.getResourceAsStream("/fixtures/policy/$name.json"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test
    fun `future legacy archive is not treated as a corrupt recoverable primary`() {
        val future = fixture("legacy-1.0").replace("\"version\": 1", "\"version\": 99")
        assertThrows(UnsupportedPolicyVersion::class.java) { PolicyWorkspaceCodec.decode(future) }
    }

    @Test
    fun `legacy fixture preserves separate platform conditions and shared channel`() {
        val workspace = PolicyWorkspaceCodec.decode(fixture("legacy-1.0"))
        assertEquals(setOf("de", "nl"), workspace.legacy.profiles.map { it.id }.toSet())
        assertEquals(1, workspace.saved.channels.size)
        assertEquals(workspace, PolicyWorkspaceCodec.decode(PolicyWorkspaceCodec.encode(workspace)))
    }

    @Test
    fun `expert fixture has the same protected folder channel and block on both platforms`() {
        val workspace = PolicyWorkspaceCodec.decode(fixture("workspace-1.1"))
        val inventory = PolicyMigration.inventory(workspace.legacy)
        RoutePlatform.entries.forEach { platform ->
            val program = PolicyProgramCompiler.compile(workspace.saved, inventory, platform)
            assertTrue(program.errors.toString(), program.isValid)
            assertEquals(4, program.rules.size)
            assertEquals(listOf("shared-europe", "shared-europe", null, null), program.rules.map { it.target.channelId })
            assertTrue(program.rules.first().protected)
            assertEquals(PolicyTarget.Folder("europe", null), program.rules.first().target.target)
            assertEquals(PolicyTarget.Block, program.rules[2].target.target)
            assertEquals(PolicyTarget.Direct, program.rules.last().target.target)
            assertEquals(workspace.saved, workspace.draft)
        }
    }
}
