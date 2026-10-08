package app.lernet.desktop.expert

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionKind
import app.lernet.routing.RuleConditions
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyBranchEditing
import app.lernet.routing.policy.PolicyCanvasKeys
import app.lernet.routing.policy.PolicyCanvasPoint
import app.lernet.routing.policy.PolicyChannel
import app.lernet.routing.policy.PolicyNode
import app.lernet.routing.policy.PolicyOtherwise
import app.lernet.routing.policy.PolicyScope
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.PolicyTree
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExpertPolicyEditingTest {
    @Test
    fun `deleting legacy node named root preserves the physical root canvas point`() {
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device, listOf(PolicyNode("root")),
                positions = mapOf(
                    PolicyCanvasKeys.ROOT to PolicyCanvasPoint(40f, 40f),
                    PolicyCanvasKeys.node("root") to PolicyCanvasPoint(40f, 240f)
                )
            )
        )
        val changed = ExpertPolicyEditing.deleteNode(policy, PolicyScope.Device, "root")
        assertThat(changed.device.positions).containsExactly(PolicyCanvasKeys.ROOT, PolicyCanvasPoint(40f, 40f))
    }

    @Test
    fun `deleting subtree prunes only its positions and retains root and shared channel`() {
        val tree = PolicyTree(
            PolicyScope.Device,
            listOf(
                PolicyNode("parent"),
                PolicyNode(
                    "child",
                    parentId =
                    "parent"
                ),
                PolicyNode("other")
            ),
            positions = mapOf(
                "root" to PolicyCanvasPoint(40f, 40f), "parent" to PolicyCanvasPoint(40f, 240f),
                "child" to PolicyCanvasPoint(40f, 440f), "other" to PolicyCanvasPoint(290f, 240f),
                "channel:shared" to PolicyCanvasPoint(40f, 670f)
            )
        )
        val changed = ExpertPolicyEditing.deleteNode(NetworkPolicy(device = tree), PolicyScope.Device, "parent")
        assertThat(changed.device.positions.keys).containsExactly("root", "other", "channel:shared")
    }

    @Test
    fun `used channel remains intact and unused channel deletion also removes its canvas point`() {
        val channel = PolicyChannel("shared", "Общий", PolicyScope.Device, PolicyTarget.Direct)
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode(
                        "rule",
                        target =
                        PolicyTarget.Channel("shared")
                    )
                ),
                positions = mapOf("channel:shared" to PolicyCanvasPoint(40f, 440f))
            ),
            channels = listOf(channel)
        )
        assertThat(ExpertPolicyEditing.deleteChannel(policy, "shared")).isEqualTo(policy)
        val detached = policy.copy(device = policy.device.copy(nodes = emptyList()))
        val changed = ExpertPolicyEditing.deleteChannel(detached, "shared")
        assertThat(changed.channels).isEmpty()
        assertThat(changed.device.positions).isEmpty()
    }

    @Test
    fun `new channel in unopened profile creates its owner tree without changing device routing`() {
        val policy = NetworkPolicy()
        val scope = PolicyScope.Profile("profile")
        val channel = PolicyChannel("channel", "Канал", scope, PolicyTarget.CurrentExit)
        val changed = ExpertPolicyEditing.putChannel(policy, channel)
        assertThat(changed.device).isEqualTo(policy.device)
        assertThat(changed.trees.single().scope).isEqualTo(scope)
        assertThat(changed.trees.single().defaultTarget).isEqualTo(PolicyTarget.CurrentExit)
        val profiles = listOf(ExpertProfile("profile", "Профиль"))
        assertThat(ExpertPolicyEditing.validation(ExpertUiState(changed, profiles = profiles))).isEmpty()
    }

    @Test
    fun `deleting last child restores terminal parent without manufactured else`() {
        val parent = PolicyNode("parent", target = PolicyTarget.Block)
        val child = PolicyNode("child", parentId = "parent")
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(parent, child)))
        val deleted = ExpertPolicyEditing.deleteNode(policy, PolicyScope.Device, "child")
        assertThat(deleted.device.nodes).containsExactly(parent)
        assertThat(deleted.device.nodes.single().target).isEqualTo(PolicyTarget.Block)
    }

    @Test
    fun `deleting branch preserves unrelated tree and shared channel identity`() {
        val channel = PolicyChannel("shared", "Канал", PolicyScope.Device, PolicyTarget.Profile("profile"))
        val profileRule = PolicyNode("profile-rule", target = PolicyTarget.CurrentExit)
        val tree = PolicyTree(PolicyScope.Profile("profile"), listOf(profileRule))
        val policy = NetworkPolicy(
            device = PolicyTree(
                PolicyScope.Device,
                listOf(
                    PolicyNode("branch"),
                    PolicyNode("child", parentId = "branch"),
                    PolicyNode("sibling", target = PolicyTarget.Channel("shared")),
                )
            ),
            trees = listOf(tree), channels = listOf(channel)
        )
        val changed = ExpertPolicyEditing.deleteNode(policy, PolicyScope.Device, "branch")
        assertThat(changed.device.nodes.map { it.id }).containsExactly("sibling")
        assertThat(changed.trees).containsExactly(tree)
        assertThat(changed.channels).containsExactly(channel)
    }

    @Test
    fun `editing windows condition does not discard android condition`() {
        val portable = PolicyNode(
            "mixed",
            conditions = RuleConditions(
                blocks = listOf(
                    ConditionBlock(ConditionKind.APP, listOf("org.example.app")),
                    ConditionBlock(ConditionKind.PROCESS, listOf("example.exe")),
                )
            )
        )
        val changed = ExpertPolicyEditing.putNode(NetworkPolicy(), PolicyScope.Device, portable)
        assertThat(changed.device.nodes.first { it.id == "mixed" }.conditions.blocks).hasSize(2)
        assertThat(changed.device.nodes.first { it.id == "mixed" }.enabled).isTrue()
    }

    @Test
    fun `reordering changes only siblings not children or saved revision`() {
        val a = PolicyNode("a", sortIndex = 0)
        val b = PolicyNode("b", sortIndex = 1)
        val child = PolicyNode("child", parentId = "a", sortIndex = 17)
        val policy = NetworkPolicy(revision = 9, device = PolicyTree(PolicyScope.Device, listOf(a, b, child)))
        val moved = ExpertPolicyEditing.moveNode(policy, PolicyScope.Device, "b", -1)
        assertThat(moved.revision).isEqualTo(9)
        assertThat(moved.device.nodes.first { it.id == "child" }).isEqualTo(child)
        assertThat(moved.device.nodes.first { it.id == "b" }.sortIndex).isEqualTo(0)
    }

    @Test
    fun `folder and profile tree edits retain device default route`() {
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = PolicyTarget.Block))
        val scope = PolicyScope.Folder("folder")
        val added = ExpertPolicyEditing.putNode(policy, scope, PolicyNode("folder-rule", target = PolicyTarget.CurrentExit))
        assertThat(added.device.defaultTarget).isEqualTo(PolicyTarget.Block)
        val rule = ExpertPolicyEditing.tree(added, scope).nodes.filterNot(PolicyOtherwise::isOtherwise).single()
        assertThat(rule.id).isEqualTo("folder-rule")
    }

    @Test
    fun `android ancestor remains inactive without deleting windows descendant`() {
        val parent = PolicyNode(
            "android",
            conditions = RuleConditions(
                blocks = listOf(
                    ConditionBlock(
                        ConditionKind.APP,
                        listOf("org.example.app")
                    )
                )
            )
        )
        val child = PolicyNode(
            "windows", parentId = parent.id,
            conditions = RuleConditions(
                blocks = listOf(
                    ConditionBlock(ConditionKind.PROCESS, listOf("example.exe")),
                )
            )
        )
        val tree = PolicyTree(PolicyScope.Device, listOf(parent, child))
        val state = ExpertUiState(NetworkPolicy(device = tree))
        assertThat(ExpertPolicyEditing.inactiveReason(child, tree, state)).contains("Android")
        assertThat(state.draft.device.nodes).containsExactly(parent, child)
    }

    @Test
    fun `invalid deep imported draft uses list without recursive graph overflow`() {
        val nodes = (0..1000).map { index ->
            PolicyNode("node_$index", parentId = if (index == 0) null else "node_${index - 1}")
        }
        val tree = PolicyTree(PolicyScope.Device, nodes)
        assertThat(ExpertPolicyEditing.graphProblem(tree)).contains("64")
        assertThat(ExpertPolicyEditing.descendants(tree, "node_0")).hasSize(1001)
    }

    @Test
    fun `cyclic draft is retained for recovery but cannot use recursive graph`() {
        val tree = PolicyTree(PolicyScope.Device, listOf(PolicyNode("a", parentId = "b"), PolicyNode("b", parentId = "a")))
        assertThat(ExpertPolicyEditing.graphProblem(tree)).contains("замкнутая")
        assertThat(tree.nodes).hasSize(2)
    }

    @Test
    fun `new condition is inserted before migrated unconditional catch all without index overflow`() {
        val otherwise = PolicyNode("otherwise", sortIndex = Int.MAX_VALUE)
        val policy = NetworkPolicy(device = PolicyTree(PolicyScope.Device, listOf(otherwise)))
        val node = PolicyNode(
            "new",
            conditions = RuleConditions(
                blocks = listOf(
                    ConditionBlock(
                        ConditionKind.DOMAIN,
                        listOf("example.invalid")
                    )
                )
            )
        )
        val changed = ExpertPolicyEditing.putNode(policy, PolicyScope.Device, node)
        val orderedIds = changed.device.nodes.sortedBy { it.sortIndex }.map { it.id }
        assertThat(orderedIds).containsExactly("new", "otherwise").inOrder()
        assertThat(changed.device.nodes.map { it.sortIndex }).containsExactly(0, 1)
    }

    @Test
    fun `external references include physical shared channels and defaults in their actual owner scope`() {
        val folder = PolicyScope.Folder("folder")
        val profileTarget = PolicyTarget.Profile("external")
        val policy = NetworkPolicy(
            device = PolicyTree(PolicyScope.Device, defaultTarget = profileTarget),
            trees = listOf(PolicyTree(folder, listOf(PolicyNode("branch", title = "Рабочий сайт", target = profileTarget)))),
            channels = listOf(PolicyChannel("channel", "Общий рабочий выход", folder, profileTarget)),
        )
        val references = externalProfileReferences(ExpertUiState(policy), "external")
        assertThat(references.map { it.scope }).containsExactly(PolicyScope.Device, folder, folder).inOrder()
        assertThat(references.map { it.title })
            .containsExactly("Путь по умолчанию", "Рабочий сайт", "Канал: Общий рабочий выход").inOrder()
        assertThat(externalProfileReferences(ExpertUiState(policy), "other-profile")).isEmpty()
    }

    @Test
    fun `adding conditions under visible otherwise preserves its physical exit for remaining traffic`() {
        val exit = PolicyTarget.Profile("profile")
        val original = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = exit))
        val otherwise = PolicyBranchEditing.displayTree(original.device).nodes.single()
        val conditional = PolicyNode(
            "local", parentId = otherwise.id,
            conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.PRIVATE, listOf("private")))),
            target = PolicyTarget.Direct,
        )
        val changed = ExpertPolicyEditing.putNode(original, PolicyScope.Device, conditional)
        val branch = changed.device.nodes.first { it.id == otherwise.id }
        assertThat(PolicyOtherwise.isOtherwise(branch)).isTrue()
        assertThat(branch.target).isEqualTo(PolicyTarget.Direct)
        val remainder = changed.device.nodes.single { it.parentId == branch.id && PolicyOtherwise.isOtherwise(it) }
        assertThat(remainder.target).isEqualTo(exit)
        assertThat(changed.device.nodes.first { it.id == "local" }.parentId).isEqualTo(branch.id)
        assertThat(original.device.nodes).isEmpty()
    }

    @Test
    fun `editing an otherwise exit retains its marker and places new siblings before it`() {
        val original = NetworkPolicy()
        val otherwise = PolicyBranchEditing.displayTree(original.device).nodes.single()
        val changed = ExpertPolicyEditing.putNode(original, PolicyScope.Device, otherwise.copy(target = PolicyTarget.Block))
        val withRule = ExpertPolicyEditing.putNode(
            changed, PolicyScope.Device,
            PolicyNode("rule", conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.GEOIP, listOf("ru"))))),
        )
        val finalBranch = withRule.device.nodes.first { it.id == otherwise.id }
        assertThat(PolicyOtherwise.isOtherwise(finalBranch)).isTrue()
        assertThat(finalBranch.target).isEqualTo(PolicyTarget.Block)
        assertThat(withRule.device.nodes.sortedBy { it.sortIndex }.map { it.id }).containsExactly("rule", otherwise.id).inOrder()
        assertThat(ExpertPolicyEditing.moveNode(withRule, PolicyScope.Device, otherwise.id, -1)).isEqualTo(withRule)
        assertThat(ExpertPolicyEditing.deleteNode(withRule, PolicyScope.Device, otherwise.id)).isEqualTo(withRule)
    }

    @Test
    fun `materialized root otherwise counts one incoming channel branch rather than obsolete default too`() {
        val target = PolicyTarget.Channel("shared")
        val original = NetworkPolicy(device = PolicyTree(PolicyScope.Device, defaultTarget = target))
        val otherwise = PolicyBranchEditing.displayTree(original.device).nodes.single()
        val changed = ExpertPolicyEditing.putNode(original, PolicyScope.Device, otherwise)
        assertThat(ExpertPolicyEditing.channelReferenceCount(changed, "shared")).isEqualTo(1)
    }
}
