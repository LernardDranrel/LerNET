package app.lernet.ui.expert

import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class ExpertEditsTest {
    @Test
    fun deletingLastChildRestoresParentsOriginalTerminalAction() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("parent", target = PolicyTarget.Block), PolicyNode("child", parentId = "parent"),
                )
            )
        )
        val result = ExpertEdits.removeNode(policy, PolicyScope.Device, "child")
        assertThat(result.device.nodes).containsExactly(PolicyNode("parent", target = PolicyTarget.Block))
    }

    @Test
    fun priorityMoveNeverReparentsOrChangesTheTargets() {
        val first = PolicyNode("first", sortIndex = 0, target = PolicyTarget.Block, protected = true)
        val second = PolicyNode("second", sortIndex = 0)
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(first, second)))
        val result = ExpertEdits.move(policy, PolicyScope.Device, "second", -1)
        assertThat(ExpertEdits.ordered(result.device).map { it.first.id }).containsExactly("second", "first").inOrder()
        assertThat(result.device.nodes.first { it.id == "first" }.copy(sortIndex = 0)).isEqualTo(first)
    }

    @Test
    fun removingSubtreePreservesOtherOwnersAndDetachedBranches() {
        val standalone = PolicyNode("detached", detached = true)
        val profile = PolicyTree(PolicyScope.Profile("p"), listOf(PolicyNode("profile-rule")))
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("root"), PolicyNode("child", parentId = "root"), PolicyNode("grandchild", parentId = "child"), standalone,
                )
            ),
            trees = listOf(profile)
        )
        val result = ExpertEdits.removeNode(policy, PolicyScope.Device, "root")
        assertThat(result.device.nodes).containsExactly(standalone)
        assertThat(result.trees).containsExactly(profile)
    }

    @Test
    fun movingRootRuleAndChannelSurvivesPortableSerializationWithoutChangingRouting() {
        val channel = PolicyChannel("shared", "Shared", PolicyScope.Device, PolicyTarget.Block)
        val initial =
            NetworkPolicy(
                device = PolicyTree(PolicyScope.Device, listOf(PolicyNode("root", target = PolicyTarget.Channel(channel.id)))),
                channels = listOf(channel)
            )
        val keys = listOf(PolicyCanvasKeys.ROOT, PolicyCanvasKeys.node("root"), PolicyCanvasKeys.channel(channel.id))
        val moved = keys.fold(initial) { policy, key ->
            ExpertEdits.position(policy, PolicyScope.Device, key, PolicyCanvasPoint(100f, 200f))
        }
        val restored = Json.decodeFromString<NetworkPolicy>(Json.encodeToString(moved))
        assertThat(restored.device.positions.keys).containsExactlyElementsIn(keys)
        assertThat(restored.device.nodes).isEqualTo(initial.device.nodes)
        assertThat(restored.channels).isEqualTo(initial.channels)
        assertThat(ExpertEdits.align(restored, PolicyScope.Device)).isEqualTo(initial)
    }

    @Test
    fun deletingReservedNodeOrSharedChannelPrunesOnlyItsOwnCanvasPoint() {
        val channel = PolicyChannel("shared", "Shared", PolicyScope.Device, PolicyTarget.Block)
        val initial = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device, listOf(PolicyNode("root")),
                positions = mapOf(
                    PolicyCanvasKeys.ROOT to PolicyCanvasPoint(10f, 10f), PolicyCanvasKeys.node("root") to PolicyCanvasPoint(20f, 20f),
                    PolicyCanvasKeys.channel(channel.id) to PolicyCanvasPoint(30f, 30f)
                )
            ),
            channels = listOf(channel)
        )
        val removed = ExpertEdits.removeChannel(ExpertEdits.removeNode(initial, PolicyScope.Device, "root"), channel.id)
        assertThat(removed.device.positions).containsExactly(PolicyCanvasKeys.ROOT, PolicyCanvasPoint(10f, 10f))
        assertThat(removed.channels).isEmpty()
    }
}
