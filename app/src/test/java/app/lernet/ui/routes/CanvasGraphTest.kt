package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CanvasGraphTest {
    @Test
    fun linksAreTheTreeAndNamedPipesAreSeparate() {
        val nodes = listOf(
            rule("a", parentId = null),
            rule("b", parentId = "a", pipeName = ""),
            rule("leaf", pipeName = "video2"),
            rule("c", action = "direct", pipeName = "video2"),
            rule("d", action = "block"),
        )
        val links = CanvasGraph.links(nodes)
        assertThat(links).contains(CanvasLink(CanvasIds.ROOT, CanvasIds.rule("a")))
        assertThat(links).contains(CanvasLink(CanvasIds.rule("a"), CanvasIds.rule("b")))
        assertThat(links.map { it.toId }).doesNotContain(CanvasIds.pipe("video2"))
        assertThat(links.map { it.toId }).doesNotContain(CanvasIds.pipe(""))
        assertThat(CanvasGraph.namedPipeLinks(nodes)).containsExactly(
            CanvasLink(CanvasIds.rule("leaf"), CanvasIds.pipe("video2")),
        )
        assertThat(CanvasGraph.pipeNames(nodes, emptyList())).containsExactly("video2")
    }

    @Test
    fun applyLinksTurnsDirectIntoAForkAndKeepsTheChildPipe() {
        val nodes = listOf(rule("a", parentId = null, action = "direct"), rule("b", parentId = null))
        val links = setOf(
            CanvasLink(CanvasIds.ROOT, CanvasIds.rule("a")),
            CanvasLink(CanvasIds.rule("a"), CanvasIds.rule("b")),
            CanvasLink(CanvasIds.rule("b"), CanvasIds.pipe("video2")),
        )
        val applied = CanvasGraph.applyLinks(nodes, links)
        assertThat(applied.rejectedCycle).isFalse()
        assertThat(applied.rejectedTerminal).isFalse()
        assertThat(applied.nodes.first { it.id == "b" }.parentId).isEqualTo("a")
        assertThat(applied.nodes.first { it.id == "b" }.pipeName).isEqualTo("video2")
    }

    @Test
    fun applyLinksRejectsAChildOnANamedPipe() {
        val nodes = listOf(
            rule("a", parentId = null, pipeName = "video2"),
            rule("b", parentId = null),
        )
        val links = setOf(
            CanvasLink(CanvasIds.ROOT, CanvasIds.rule("a")),
            CanvasLink(CanvasIds.rule("a"), CanvasIds.rule("b")),
        )
        val applied = CanvasGraph.applyLinks(nodes, links)
        assertThat(applied.rejectedTerminal).isTrue()
        assertThat(applied.nodes.first { it.id == "b" }.parentId).isNull()
        assertThat(CanvasGraph.namedPipeLinks(nodes)).containsExactly(
            CanvasLink(CanvasIds.rule("a"), CanvasIds.pipe("video2")),
        )
    }

    @Test
    fun applyLinksAllowsFirstChildOfAutoProxy() {
        val nodes = listOf(rule("a"), rule("b"))
        val links = setOf(
            CanvasLink(CanvasIds.ROOT, CanvasIds.rule("a")),
            CanvasLink(CanvasIds.rule("a"), CanvasIds.rule("b")),
        )
        val applied = CanvasGraph.applyLinks(nodes, links)
        assertThat(applied.rejectedTerminal).isFalse()
        assertThat(applied.nodes.first { it.id == "b" }.parentId).isEqualTo("a")
    }

    @Test
    fun applyLinksKeepsAnExistingChildUntilItIsLifted() {
        val nodes = listOf(rule("a"), rule("b", parentId = "a", action = "direct"))
        val applied = CanvasGraph.applyLinks(nodes, CanvasGraph.links(nodes))
        assertThat(applied.rejectedTerminal).isFalse()
        assertThat(applied.nodes.first { it.id == "b" }.parentId).isEqualTo("a")
    }

    @Test
    fun applyLinksLiftsAChildBackToRoot() {
        val nodes = listOf(rule("a", action = "block"), rule("b", parentId = "a"))
        val links = setOf(
            CanvasLink(CanvasIds.ROOT, CanvasIds.rule("a")),
            CanvasLink(CanvasIds.ROOT, CanvasIds.rule("b")),
        )
        val applied = CanvasGraph.applyLinks(nodes, links)
        assertThat(applied.rejectedTerminal).isFalse()
        assertThat(applied.nodes.first { it.id == "b" }.parentId).isNull()
    }

    @Test
    fun applyLinksKeepsParentWhenTheNewEdgeCycles() {
        val nodes = listOf(rule("a", parentId = null), rule("b", parentId = "a"))
        val links = setOf(
            CanvasLink(CanvasIds.rule("b"), CanvasIds.rule("a")),
            CanvasLink(CanvasIds.ROOT, CanvasIds.rule("b")),
        )
        val applied = CanvasGraph.applyLinks(nodes, links)
        assertThat(applied.rejectedCycle).isTrue()
        assertThat(applied.rejectedTerminal).isFalse()
        assertThat(applied.nodes.first { it.id == "a" }.parentId).isNull()
    }

    @Test
    fun applyLinksRejectsCycleCreatedByTwoNewEdges() {
        val nodes = listOf(rule("a"), rule("b"))
        val links = setOf(
            CanvasLink(CanvasIds.rule("a"), CanvasIds.rule("b")),
            CanvasLink(CanvasIds.rule("b"), CanvasIds.rule("a")),
        )
        val applied = CanvasGraph.applyLinks(nodes, links)
        assertThat(applied.rejectedCycle).isTrue()
        val a = applied.nodes.first { it.id == "a" }
        val b = applied.nodes.first { it.id == "b" }
        assertThat(a.parentId == "b" && b.parentId == "a").isFalse()
    }

    @Test
    fun layoutLeavesSortIndexAlone() {
        val nodes = listOf(rule("b", sort = 1), rule("a", sort = 0))
        val placed = CanvasGraph.layout(nodes, emptyList(), emptyMap())
        assertThat(nodes.map { it.id to it.sortIndex }).containsExactly("b" to 1, "a" to 0).inOrder()
        assertThat(placed.getValue(CanvasIds.rule("a")).x).isLessThan(placed.getValue(CanvasIds.rule("b")).x)
    }

    @Test
    fun forkAcceptsAnotherChild() {
        val nodes = listOf(rule("a"), rule("else", parentId = "a"), rule("b"))
        val links = setOf(
            CanvasLink(CanvasIds.ROOT, CanvasIds.rule("a")),
            CanvasLink(CanvasIds.rule("a"), CanvasIds.rule("else")),
            CanvasLink(CanvasIds.rule("a"), CanvasIds.rule("b")),
        )
        val applied = CanvasGraph.applyLinks(nodes, links)
        assertThat(applied.rejectedTerminal).isFalse()
        assertThat(applied.nodes.first { it.id == "b" }.parentId).isEqualTo("a")
    }

    @Test
    fun layoutRoundTripKeepsPipeNamesAndReflowsSavedPoints() {
        val saved = mapOf(
            CanvasIds.AXIS to CanvasPoint(1f, 0f),
            CanvasIds.ROOT to CanvasPoint(12f, 34f),
            CanvasIds.SYSTEM to CanvasPoint(40f, 24f),
            CanvasIds.pipe("free") to CanvasPoint(620f, 36f),
        )
        val encoded = CanvasGraph.encode(saved)
        val decoded = CanvasGraph.decode(encoded)
        val placed = CanvasGraph.layout(emptyList(), listOf(""), decoded)
        assertThat(placed[CanvasIds.ROOT]).isEqualTo(CanvasPoint(40f, 36f))
        assertThat(placed[CanvasIds.SYSTEM]).isEqualTo(CanvasPoint(40f, 8f))
        assertThat(CanvasGraph.savedPipeNames(decoded)).containsExactly("free")
    }

    @Test
    fun layoutPitchSeparatesSiblingsByTheGivenColumn() {
        val placed = CanvasGraph.layout(
            listOf(rule("a"), rule("b", sort = 1)),
            emptyList(),
            emptyMap(),
            column = 900f,
            row = 800f,
        )
        val left = placed.getValue(CanvasIds.rule("a"))
        val right = placed.getValue(CanvasIds.rule("b"))
        assertThat(right.x - left.x).isWithin(0.01f).of(900f)
        assertThat(left.y).isEqualTo(right.y)
    }

    @Test
    fun namedPipeSitsBelowTheTreeAndUnderItsSourceRule() {
        val nodes = listOf(
            rule("leaf", pipeName = "video"),
            rule("fork", sort = 1),
            rule("deep", parentId = "fork"),
        )
        val placed = CanvasGraph.layout(nodes, listOf("video"), emptyMap())
        val source = placed.getValue(CanvasIds.rule("leaf"))
        val deepest = placed.getValue(CanvasIds.rule("deep"))
        val pipe = placed.getValue(CanvasIds.pipe("video"))

        assertThat(pipe.x).isEqualTo(source.x)
        assertThat(pipe.y).isGreaterThan(deepest.y)
    }

    @Test
    fun missingAxisReflowsRootAboveTheChild() {
        val placed = CanvasGraph.layout(listOf(rule("a")), listOf(""), emptyMap())
        val root = placed.getValue(CanvasIds.ROOT)
        val child = placed.getValue(CanvasIds.rule("a"))
        assertThat(CanvasGraph.isVertical(placed)).isTrue()
        assertThat(child.y).isGreaterThan(root.y)
    }

    @Test
    fun linksSkipOrphans() {
        val nodes = listOf(rule("kept"), rule("loose", parentId = RouteFolders.ORPHAN))
        val links = CanvasGraph.links(nodes)
        assertThat(links.map { it.toId }).contains(CanvasIds.rule("kept"))
        assertThat(links.map { it.toId }).doesNotContain(CanvasIds.rule("loose"))
    }

    private fun rule(
        id: String,
        parentId: String? = null,
        pipeName: String = "",
        sort: Int = 0,
        action: String = "proxy",
    ): RuleNodeRecord = RuleNodeRecord(
        id = id,
        profileId = "p",
        parentId = parentId,
        enabled = true,
        sortIndex = sort,
        action = action,
        apps = emptyList(),
        domains = if (id == "else") emptyList() else listOf("$id.example"),
        domainSuffixes = emptyList(),
        ipCidrs = emptyList(),
        geoip = emptyList(),
        pipeName = pipeName,
    )
}
