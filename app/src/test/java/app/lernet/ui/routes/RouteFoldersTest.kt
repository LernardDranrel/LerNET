package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import app.lernet.routing.ConditionCodec
import app.lernet.routing.MatchJoin
import app.lernet.routing.RouteElse
import app.lernet.routing.RuleConditions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RouteFoldersTest {
    @Test
    fun listOrderIsSiblingPriorityAndSkipsOtherParents() {
        val nodes = listOf(
            rule("later", sort = 2, domains = listOf("later.example")),
            rule("earlier", sort = 0, domains = listOf("early.example")),
            rule("dead", sort = 1, action = "block", domains = listOf("dead.example")),
            rule("hung", parentId = "dead", domains = listOf("hung.example")),
            rule("child", parentId = "earlier", domains = listOf("child.example")),
            rule("loose", parentId = RouteFolders.ORPHAN, action = "block"),
        )
        assertThat(RouteFolders.listed(nodes, folderId = null).map { it.id })
            .containsExactly("earlier", "dead", "later")
            .inOrder()
        assertThat(RouteFolders.listed(nodes, "earlier").map { it.id }).containsExactly("child")
        assertThat(RouteFolders.hasNested(nodes)).isFalse()
        assertThat(RouteFolders.misplaced(nodes, nodes.first { it.id == "hung" })).isFalse()
        assertThat(RouteFolders.misplaced(nodes, nodes.first { it.id == "loose" })).isFalse()
        assertThat(RouteFolders.addParent(nodes, "earlier")).isEqualTo("earlier")
        assertThat(RouteFolders.addParent(nodes, null)).isNull()
        assertThat(RouteFolders.addParent(nodes, "dead")).isEqualTo("dead")
        assertThat(
            RouteFolders.selectedParent(
                nodes,
                asList = false,
                listFolderId = null,
                canvasSelection = CanvasIds.rule("earlier"),
            ),
        ).isEqualTo("earlier")
        assertThat(
            RouteFolders.selectedParent(
                nodes,
                asList = false,
                listFolderId = null,
                canvasSelection = CanvasIds.rule("dead"),
            ),
        ).isEqualTo("dead")
        assertThat(RouteFolders.priorityRank(nodes, nodes.first { it.id == "child" })).isEqualTo(1)
        assertThat(RouteFolders.priorityRank(nodes, nodes.first { it.id == "later" })).isEqualTo(3)
    }

    @Test
    fun deleteConfirmsChildrenAndTheElseButNotAPlainLeaf() {
        val nodes = listOf(
            rule("leaf", domains = listOf("a.example")),
            rule("parent", domains = listOf("b.example")),
            rule("child", parentId = "parent", domains = listOf("c.example")),
            rule("tail"),
        )
        assertThat(RouteFolders.deleteNeedsConfirm(nodes, "leaf")).isFalse()
        assertThat(RouteFolders.deleteNeedsConfirm(nodes, "parent")).isTrue()
        assertThat(RouteFolders.deleteNeedsConfirm(nodes, "tail")).isTrue()
        assertThat(RouteFolders.deleteNeedsConfirm(nodes, "missing")).isFalse()
    }

    @Test
    fun deletingTheOnlyChildElseRestoresItsOutcomeToTheParent() {
        val parent = rule("parent", domains = listOf("parent.example"), action = "proxy")
        val fallback = rule("fallback", parentId = "parent", action = "direct")
        val nodes = listOf(parent, fallback, rule("root-tail"))

        assertThat(RouteFolders.deleteNeedsConfirm(nodes, "fallback")).isFalse()
        val restored = RouteFolders.restoreTerminalAfterElseDeletion(nodes, "fallback")
            .filterNot { it.id == "fallback" }
        val seeded = RouteFolders.seedMissingElse(restored, "p", { "unexpected" })

        assertThat(seeded.first { it.id == "parent" }.action).isEqualTo("direct")
        assertThat(RouteFolders.children(seeded, "parent")).isEmpty()
        assertThat(seeded.map { it.id }).containsExactly("parent", "root-tail")
    }

    @Test
    fun deletingTheElseReseedsThatLevelAndSkipsOrphans() {
        val nodes = listOf(
            rule("keep", domains = listOf("keep.example")),
            rule("tail"),
            rule("loose", parentId = RouteFolders.ORPHAN, action = "block"),
        )
        val kept = nodes.filterNot { it.id == "tail" }
        val seeded = RouteFolders.seedMissingElse(kept, "p", { "new-else" })
        val root = RouteFolders.listed(seeded, null)
        assertThat(root.map { it.id }).containsExactly("keep", "new-else").inOrder()
        assertThat(root.last().isElseRule()).isTrue()
        assertThat(seeded.map { it.id }).contains("loose")
        assertThat(RouteFolders.seedMissingElse(seeded, "p", { "again" }).map { it.id })
            .containsExactlyElementsIn(seeded.map { it.id })
    }

    @Test
    fun pinElseLastKeepsTheEmptyRuleAtTheEnd() {
        val nodes = listOf(
            rule("tail").copy(sortIndex = 0),
            rule("a").copy(sortIndex = 2, domains = listOf("a.example")),
        )
        val pinned = RouteFolders.pinElseLast(nodes)
        assertThat(pinned.first { it.id == "a" }.sortIndex).isEqualTo(0)
        assertThat(pinned.first { it.id == "tail" }.sortIndex).isEqualTo(1)
    }

    @Test
    fun firstDraftChildUnderASelectedParentSeedsElseLast() {
        val parent = rule("parent", domains = listOf("parent.example"))
        val draft = rule("draft", parentId = "parent").copy(
            blocksJson = ConditionCodec.encode(RuleConditions(MatchJoin.OR, emptyList())),
        )
        assertThat(draft.isElseRule()).isFalse()
        val seeded = RouteFolders.seedMissingElse(listOf(parent, draft), "p", newId = { "auto-else" })
        val kids = RouteFolders.listed(seeded, "parent")
        assertThat(kids.map { it.id }).containsExactly("draft", "auto-else").inOrder()
        assertThat(kids.last().isElseRule()).isTrue()
        assertThat(kids.last().blocksJson).isEmpty()
        assertThat(RouteFolders.priorityRank(seeded, draft)).isEqualTo(1)
        assertThat(RouteFolders.priorityRank(seeded, kids.last())).isEqualTo(2)
    }

    @Test
    fun aSecondNonElseSiblingKeepsElseLastAndRanksOnlyRealRules() {
        val nodes = listOf(
            rule("parent", domains = listOf("parent.example")),
            rule("a", parentId = "parent", domains = listOf("a.example"), sort = 0),
            rule("tail", parentId = "parent", sort = 1),
        )
        val second = rule("b", parentId = "parent", domains = listOf("b.example"), sort = 1)
        val next = RouteFolders.seedMissingElse(
            nodes.filterNot { it.id == "tail" } + second + nodes.first { it.id == "tail" }.copy(sortIndex = 2),
            "p",
            newId = { "nope" },
        )
        val kids = RouteFolders.listed(next, "parent")
        assertThat(kids.map { it.id }).containsExactly("a", "b", "tail").inOrder()
        assertThat(kids.last().isElseRule()).isTrue()
        assertThat(RouteFolders.priorityRank(next, kids[0])).isEqualTo(1)
        assertThat(RouteFolders.priorityRank(next, kids[1])).isEqualTo(2)
    }

    @Test
    fun theFirstRealChildUnderAForkGetsAnAutoPipeElseAfterIt() {
        val nodes = listOf(
            rule("fork", domains = listOf("fork.example")),
            rule("kid", parentId = "fork", domains = listOf("kid.example")),
        )
        val ids = mutableListOf("root-else", "fork-else")
        val seeded = RouteFolders.seedMissingElse(nodes, "p", { ids.removeAt(0) })
        val kids = RouteFolders.listed(seeded, "fork")
        assertThat(kids.map { it.id }).containsExactly("kid", "fork-else").inOrder()
        val tail = kids.last()
        assertThat(tail.isElseRule()).isTrue()
        assertThat(tail.action).isEqualTo("proxy")
        assertThat(tail.pipeName).isEmpty()
        assertThat(tail.enabled).isTrue()
    }

    @Test
    fun openingAForkWithNoChildrenYetStillSeedsAutoPipeElse() {
        val nodes = listOf(rule("fork", domains = listOf("fork.example")))
        val ids = mutableListOf("root-else", "fork-else")
        val seeded = RouteFolders.seedMissingElse(nodes, "p", { ids.removeAt(0) }, setOf("fork"))
        val kids = RouteFolders.listed(seeded, "fork")
        assertThat(kids.map { it.id }).containsExactly("fork-else")
        assertThat(kids.single().isElseRule()).isTrue()
        assertThat(kids.single().action).isEqualTo("proxy")
        assertThat(kids.single().pipeName).isEmpty()
    }

    @Test
    fun anExistingElseIsPinnedLastWithoutASecondCopy() {
        val nodes = listOf(
            rule("tail").copy(sortIndex = 0),
            rule("a", sort = 1, domains = listOf("a.example")),
        )
        val seeded = RouteFolders.seedMissingElse(nodes, "p", { "nope" })
        assertThat(seeded.map { it.id }).containsExactly("tail", "a")
        assertThat(seeded.first { it.id == "a" }.sortIndex).isEqualTo(0)
        assertThat(seeded.first { it.id == "tail" }.sortIndex).isEqualTo(1)
    }

    @Test
    fun elseDragAndRepairOnlyCareAboutAMissingOrMovedTail() {
        val rows = listOf(
            rule("a", domains = listOf("a.example")),
            rule("tail"),
        )
        assertThat(RouteFolders.elseMoveBlocked(rows, 1, 0)).isTrue()
        assertThat(RouteFolders.elseMoveBlocked(rows, 0, 1)).isTrue()
        assertThat(RouteFolders.elseMoveBlocked(rows, 0, 0)).isFalse()
        assertThat(RouteFolders.elseNeedsRepair(listOf("else: ${RouteElse.MISSING}"))).isTrue()
        assertThat(RouteFolders.elseNeedsRepair(listOf("else: ${RouteElse.NOT_LAST}"))).isTrue()
        assertThat(RouteFolders.elseNeedsRepair(listOf("else: ${RouteElse.EXTRA}"))).isFalse()
        assertThat(RouteFolders.elseNeedsRepair(listOf("else: ${RouteElse.DISABLED}"))).isFalse()
    }

    @Test
    fun adoptPutsAnOrphanBeforeTheElseAndIgnoresAnAttachedNode() {
        val nodes = listOf(
            rule("fork", domains = listOf("fork.example")),
            rule("kid", parentId = "fork", domains = listOf("kid.example")),
            rule("fork-else", parentId = "fork"),
            rule("loose", parentId = RouteFolders.ORPHAN, action = "block", domains = listOf("loose.example")),
            rule("root-else"),
        )
        val adopted = RouteFolders.adopt(nodes, "loose", "fork", "p") { "nope" }
        assertThat(RouteFolders.listed(adopted, "fork").map { it.id })
            .containsExactly("kid", "loose", "fork-else")
            .inOrder()
        val ignored = RouteFolders.adopt(adopted, "kid", null, "p") { "nope" }
        assertThat(ignored.first { it.id == "kid" }.parentId).isEqualTo("fork")
        assertThat(RouteFolders.orphans(adopted)).isEmpty()
    }

    @Test
    fun insideAFolderOnlyDirectChildrenAreListed() {
        val nodes = listOf(rule("root"), rule("child", parentId = "root"))
        assertThat(RouteFolders.listed(nodes, "root").map { it.id }).containsExactly("child")
    }

    @Test
    fun branchingPreservesEveryLeafOutcomeInAnEnabledElse() {
        listOf("proxy", "direct", "block").forEach { action ->
            val parent = rule("parent", action = action, domains = listOf("example.com"))
                .copy(pipeName = if (action == "proxy") "video" else "", enabled = false)
            val branched = RouteFolders.branchPreservingOutcome(listOf(parent), "parent") { "fallback" }
            val fork = branched.first { it.id == "parent" }
            val fallback = branched.first { it.id == "fallback" }
            assertThat(fork.pipeName).isEmpty()
            assertThat(fork.acceptsChildren(branched)).isTrue()
            assertThat(fallback.isElseRule()).isTrue()
            assertThat(fallback.enabled).isTrue()
            assertThat(fallback.action).isEqualTo(action)
            assertThat(fallback.pipeName).isEqualTo(parent.pipeName)
            assertThat(fallback.canAdoptChild()).isEqualTo(fallback.pipeName.isBlank())
            assertThat(RouteFolders.branchPreservingOutcome(branched, "parent") { "duplicate" }).isEqualTo(branched)
        }
    }

    @Test
    fun otherwiseCanBranchRepeatedlyWhilePreservingTheTerminalPipe() {
        val fallback = rule("else").copy(pipeName = "video")
        assertThat(RouteFolders.addParent(listOf(fallback), fallback.id)).isEqualTo("else")
        val first = RouteFolders.branchPreservingOutcome(listOf(fallback), fallback.id) { "child-else" }
        val nested = RouteFolders.branchPreservingOutcome(first, "child-else") { "deep-else" }
        assertThat(nested.map { it.id }).containsExactly("else", "child-else", "deep-else").inOrder()
        assertThat(nested.first { it.id == "else" }.pipeName).isEmpty()
        assertThat(nested.first { it.id == "child-else" }.pipeName).isEmpty()
        assertThat(nested.first { it.id == "deep-else" }.pipeName).isEqualTo("video")
        assertThat(nested.all { it.isElseRule() && it.enabled }).isTrue()
        assertThat(nested.first { it.id == "else" }.acceptsChildren(nested)).isTrue()
        assertThat(nested.first { it.id == "child-else" }.acceptsChildren(nested)).isTrue()
    }

    private fun rule(
        id: String,
        parentId: String? = null,
        action: String = "proxy",
        sort: Int = 0,
        domains: List<String> = emptyList(),
    ): RuleNodeRecord = RuleNodeRecord(
        id = id,
        profileId = "p",
        parentId = parentId,
        enabled = true,
        sortIndex = sort,
        action = action,
        apps = emptyList(),
        domains = domains,
        domainSuffixes = emptyList(),
        ipCidrs = emptyList(),
        geoip = emptyList(),
        pipeName = "",
    )
}
