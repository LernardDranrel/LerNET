package app.lernet.desktop

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionJson
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RuleConditions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DesktopRouteTreeTest {
    @Test fun sharedNamedChannelHasOneEntryWithBothSources() {
        val rules = listOf(
            rule("left").copy(pipeName = "office"),
            rule("parent", sort = 1),
            rule("right", parent = "parent").copy(pipeName = "office"),
            rule("inactive", sort = 2).copy(action = "DIRECT", pipeName = "office"),
        )
        val channels = namedChannelSources(rules, "profile")
        assertThat(channels.keys).containsExactly("office")
        assertThat(channels.getValue("office").map { it.id }).containsExactly("left", "right").inOrder()
    }

    @Test fun profileDropReordersAndMovesIntoOneLevelFolder() {
        val directory = java.nio.file.Files.createTempDirectory("lernet-profile-drop")
        val profiles = listOf("a", "b", "c").map { id ->
            StoredProfile(id, id, "JSON", emptyList(), "")
        }
        val store = DesktopStore(directory)
        store.save(StoredState(profiles = profiles, groups = listOf(StoredGroup("folder", "Семья"))))
        val controller = DesktopController(store)
        try {
            controller.dropProfile("c", "a", null, false)
            assertThat(controller.state.value.saved.profiles.map { it.id }).containsExactly("c", "a", "b").inOrder()
            assertThat(controller.state.value.saved.groups.map { it.id }).contains("folder")
            controller.dropProfile("a", null, "folder", false)
            assertThat(controller.state.value.message).isEmpty()
            assertThat(controller.state.value.saved.profiles.single { it.id == "a" }.groupId).isEqualTo("folder")
            controller.setGroup("b", "folder")
            assertThat(controller.state.value.saved.profiles.single { it.id == "b" }.groupId).isEqualTo("folder")
            assertThat(DesktopStore(directory).load().profiles.single { it.id == "b" }.groupId).isEqualTo("folder")
            controller.duplicateProfile("a")
            assertThat(controller.state.value.saved.profiles.count { it.groupId == "folder" }).isEqualTo(3)
        } finally {
            controller.close()
        }
    }

    private fun rule(id: String, parent: String? = null, sort: Int = 0) = StoredRule(
        id = id, profileId = "profile", parentId = parent, sortIndex = sort,
        domains = listOf("$id.example"),
    )

    @Test fun savingPriorityMovesRuleWithoutChangingItsConditions() {
        val first = rule("a", sort = 0)
        val second = rule("b", sort = 1)
        val fallback = StoredRule("else", "profile", sortIndex = 2, title = "Иначе")
        val saved = DesktopRouteTree.save(listOf(first, second, fallback), second.copy(sortIndex = 0))
        assertThat(saved.error).isNull()
        assertThat(DesktopRouteTree.siblings(saved.rules, "profile", null).map { it.id })
            .containsExactly("b", "a", "else").inOrder()
        assertThat(saved.rules.single { it.id == "b" }.domains).containsExactly("b.example")
    }

    @Test fun listAndSchemeUseSameSavedTree() {
        val parent = rule("parent")
        val root = DesktopRouteTree.save(emptyList(), parent)
        assertThat(root.error).isNull()
        assertThat(
            DesktopRouteTree.siblings(root.rules, "profile", null).map {
                it.id
            }
        ).containsExactly("parent", root.rules.last().id).inOrder()

        val child = rule("child", "parent")
        val nested = DesktopRouteTree.save(root.rules, child)
        assertThat(nested.error).isNull()
        assertThat(DesktopRouteTree.siblings(nested.rules, "profile", "parent").size).isEqualTo(2)
        assertThat(nested.rules.count(DesktopRouteTree::isElse)).isEqualTo(2)

        val deleted = DesktopRouteTree.delete(nested.rules, "parent")
        assertThat(deleted.rules.none { it.id == "parent" || it.id == "child" }).isTrue()
        assertThat(deleted.rules.size).isEqualTo(1)
    }

    @Test fun directLeafBecomesForkAndKeepsItsOutcomeInElse() {
        val parent = rule("parent").copy(action = "DIRECT")
        val root = DesktopRouteTree.save(emptyList(), parent).rules
        val nested = DesktopRouteTree.save(root, rule("child", "parent"))
        assertThat(nested.error).isNull()
        val fallback = DesktopRouteTree.siblings(nested.rules, "profile", "parent").last()
        assertThat(DesktopRouteTree.isElse(fallback)).isTrue()
        assertThat(fallback.action).isEqualTo("DIRECT")
        assertThat(nested.rules.single { it.id == "parent" }.action).isEqualTo("DIRECT")
    }

    @Test fun namedPipeBecomesForkAndItsElseKeepsThePipe() {
        val parent = rule("parent").copy(pipeName = "office")
        val root = DesktopRouteTree.save(emptyList(), parent).rules
        val nested = DesktopRouteTree.save(root, rule("child", "parent"))
        assertThat(nested.error).isNull()
        assertThat(nested.rules.single { it.id == "parent" }.pipeName).isEmpty()
        assertThat(DesktopRouteTree.siblings(nested.rules, "profile", "parent").last().pipeName).isEqualTo("office")
    }

    @Test fun otherwiseBranchCanExpandWithoutLosingItsFormerOutcome() {
        val base = DesktopRouteTree.save(emptyList(), rule("ordinary")).rules
        val outerElse = base.single(DesktopRouteTree::isElse).copy(action = "DIRECT", pipeName = "fallback")
        val configured = base.map { if (it.id == outerElse.id) outerElse else it }
        val expanded = DesktopRouteTree.save(configured, rule("nested", outerElse.id))
        assertThat(expanded.error).isNull()
        assertThat(expanded.rules.single { it.id == outerElse.id }.pipeName).isEmpty()
        val innerElse = DesktopRouteTree.siblings(expanded.rules, "profile", outerElse.id).last()
        assertThat(DesktopRouteTree.isElse(innerElse)).isTrue()
        assertThat(innerElse.action).isEqualTo("DIRECT")
        assertThat(innerElse.pipeName).isEqualTo("fallback")
    }

    @Test fun dragReordersOnlyWithinSiblingLevel() {
        val roots = DesktopRouteTree.save(DesktopRouteTree.save(emptyList(), rule("a")).rules, rule("b", sort = 1)).rules
        val moved = DesktopRouteTree.reorder(roots, "b", "a", after = false)
        assertThat(DesktopRouteTree.siblings(moved.rules, "profile", null).map { it.id }.first()).isEqualTo("b")
        val withChild = DesktopRouteTree.save(moved.rules, rule("child", "a")).rules
        val ignored = DesktopRouteTree.reorder(withChild, "child", "b", after = false)
        assertThat(ignored.rules).isEqualTo(withChild)
    }

    @Test fun rejectsCyclesAndKeepsOtherwiseLast() {
        val first = DesktopRouteTree.save(emptyList(), rule("first")).rules
        val second = DesktopRouteTree.save(first, rule("second", sort = 1)).rules
        val elseRule = second.single(DesktopRouteTree::isElse)
        assertThat(DesktopRouteTree.siblings(second, "profile", null).last().id).isEqualTo(elseRule.id)
        assertThat(DesktopRouteTree.move(second, elseRule.id, -1).error).isNotNull()

        val withChild = DesktopRouteTree.save(second, rule("child", "first")).rules
        assertThat(DesktopRouteTree.save(withChild, withChild.first { it.id == "first" }.copy(parentId = "child")).error)
            .contains("самого себя")
    }

    @Test fun joinsWindowsProcessesWithOtherConditions() {
        val blocks = listOf(
            ConditionBlock(ConditionKind.DOMAIN, listOf("example.com")),
            ConditionBlock(ConditionKind.PROCESS, listOf("browser.exe")),
        )
        val and = ConditionJson.of(RuleConditions(MatchJoin.AND, blocks)).toString()
        val or = ConditionJson.of(RuleConditions(MatchJoin.OR, blocks)).toString()
        assertThat(and).contains("process_name")
        assertThat(and).doesNotContain("\"mode\":\"or\"")
        assertThat(or).contains("\"mode\":\"or\"")
    }
}
