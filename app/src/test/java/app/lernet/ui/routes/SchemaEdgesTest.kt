package app.lernet.ui.routes

import app.lernet.config.repo.RuleNodeRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SchemaEdgesTest {
    @Test
    fun screenRectsFollowTheCanvasScaleAndOffset() {
        val world = SchemaEdges.worldRect(0f, 0f, 100f, 80f)
        val screen = world.toScreen(scale = 0.5f, offsetX = 40f, offsetY = 20f)
        assertThat(screen).isEqualTo(NodeRect(40f, 20f, 90f, 60f))
        val child = SchemaEdges.worldRect(0f, 200f, 100f, 80f).toScreen(0.5f, 40f, 20f)
        val nodes = listOf(rule("child"))
        val rects = mapOf(CanvasIds.ROOT to screen, CanvasIds.rule("child") to child)
        val segment = SchemaEdges.segments(nodes, rects, vertical = true, scale = 0.5f).single()
        assertThat(segment.y0).isEqualTo(60f)
        assertThat(segment.y1).isEqualTo(120f)
        val mid = SchemaEdges.pointAt(segment, 0.5f)
        assertThat(SchemaEdges.pick(segments = listOf(segment), cards = rects.values, x = mid.first, y = mid.second, slop = 24f))
            .isEqualTo(segment.edge)
    }

    @Test
    fun aPointOnTheCurveSelectsAndAPointInsideTheCardDoesNot() {
        val parent = NodeRect(0f, 0f, 100f, 80f)
        val child = NodeRect(0f, 200f, 100f, 280f)
        val nodes = listOf(rule("child"))
        val rects = mapOf(CanvasIds.ROOT to parent, CanvasIds.rule("child") to child)
        val segments = SchemaEdges.segments(nodes, rects, vertical = true, scale = 1f)
        val edge = SchemaEdge(SchemaEdgeKind.TREE, CanvasIds.ROOT, CanvasIds.rule("child"))
        val mid = SchemaEdges.pointAt(segments.single(), 0.5f)
        assertThat(SchemaEdges.pick(segments, rects.values, mid.first, mid.second, slop = 24f)).isEqualTo(edge)
        assertThat(SchemaEdges.pick(segments, rects.values, mid.first + 40f, mid.second, slop = 24f)).isNull()
        assertThat(SchemaEdges.pick(segments, rects.values, 50f, 40f, slop = 24f)).isNull()
        assertThat(SchemaEdges.pick(segments, rects.values, mid.first + 20f, mid.second, slop = 24f)).isEqualTo(edge)
    }

    @Test
    fun aStraightPipeHitsItsMiddleAndMissesTheCard() {
        val rule = NodeRect(0f, 0f, 80f, 40f)
        val pipe = NodeRect(200f, 0f, 280f, 40f)
        val nodes = listOf(rule("leaf", pipeName = "video"))
        val rects = mapOf(CanvasIds.rule("leaf") to rule, CanvasIds.pipe("video") to pipe)
        val segments = SchemaEdges.segments(nodes, rects, vertical = true, scale = 1f)
        val pipeEdge = segments.single { it.edge.kind == SchemaEdgeKind.PIPE }
        val mid = SchemaEdges.pointAt(pipeEdge, 0.5f)
        assertThat(pipeEdge.straight).isTrue()
        assertThat(SchemaEdges.pick(segments, rects.values, mid.first, mid.second, slop = 24f))
            .isEqualTo(pipeEdge.edge)
        assertThat(SchemaEdges.pick(segments, rects.values, 20f, 20f, slop = 48f)).isNull()
    }

    @Test
    fun breakingAChildOrphansItAndKeepsItsDescendant() {
        val nodes = listOf(
            rule("parent", domains = listOf("parent.example")),
            rule("child", parentId = "parent", domains = listOf("child.example")),
            rule("sibling-else", parentId = "parent"),
            rule("grand", parentId = "child", domains = listOf("grand.example")),
            rule("grand-else", parentId = "child"),
            rule("tail"),
        )
        val next = SchemaEdges.afterTreeBreak(nodes, "p", "child") { "unused" }
        assertThat(next.map { it.id }).containsExactlyElementsIn(nodes.map { it.id })
        assertThat(next.first { it.id == "child" }.parentId).isEqualTo(RouteFolders.ORPHAN)
        assertThat(next.first { it.id == "grand" }.parentId).isEqualTo("child")
        assertThat(next.first { it.id == "sibling-else" }.parentId).isEqualTo("parent")
    }

    @Test
    fun breakingTheElseOrphansItAndReseedsThatLevel() {
        val nodes = listOf(
            rule("keep", domains = listOf("keep.example")),
            rule("tail"),
        )
        val edge = SchemaEdge(SchemaEdgeKind.TREE, CanvasIds.ROOT, CanvasIds.rule("tail"))
        assertThat(SchemaEdges.confirmBeforeBreak(nodes, edge)).isEqualTo(EdgeConfirm.ELSE)
        val next = SchemaEdges.afterTreeBreak(nodes, "p", "tail") { "new-else" }
        assertThat(next.first { it.id == "tail" }.parentId).isEqualTo(RouteFolders.ORPHAN)
        val root = RouteFolders.listed(next, null)
        assertThat(root.map { it.id }).containsExactly("keep", "new-else").inOrder()
        assertThat(root.last().isElseRule()).isTrue()
    }

    @Test
    fun edgeToneIsGreenForDirectYellowForBypassAndRedForBlock() {
        val nodes = listOf(
            rule("direct", action = "direct"),
            rule("block", action = "block"),
            rule("auto"),
            rule("named", pipeName = "video"),
        )
        assertThat(toneOf(nodes, SchemaEdgeKind.PIPE, "named", CanvasIds.pipe("video"))).isEqualTo(RouteTone.NAMED)
        assertThat(toneOf(nodes, SchemaEdgeKind.TREE, CanvasIds.ROOT, CanvasIds.rule("direct"))).isEqualTo(RouteTone.DIRECT)
        assertThat(toneOf(nodes, SchemaEdgeKind.TREE, CanvasIds.ROOT, CanvasIds.rule("block"))).isEqualTo(RouteTone.BLOCK)
        assertThat(toneOf(nodes, SchemaEdgeKind.TREE, CanvasIds.ROOT, CanvasIds.rule("auto"))).isEqualTo(RouteTone.VIA)
        assertThat(toneOf(nodes, SchemaEdgeKind.TREE, CanvasIds.ROOT, CanvasIds.rule("named"))).isEqualTo(RouteTone.NAMED)
        assertThat(RouteTone.NAMED.ink()).isEqualTo(RouteTone.VIA.ink())
        assertThat(RouteTone.DIRECT.ink()).isNotEqualTo(RouteTone.BLOCK.ink())
    }

    @Test
    fun aPlainLeafBreaksWithoutAConfirmAndAPipeAsks() {
        val nodes = listOf(rule("leaf", domains = listOf("a.example"), pipeName = "video"))
        val tree = SchemaEdge(SchemaEdgeKind.TREE, CanvasIds.ROOT, CanvasIds.rule("leaf"))
        val pipe = SchemaEdge(SchemaEdgeKind.PIPE, CanvasIds.rule("leaf"), CanvasIds.pipe("video"))
        assertThat(SchemaEdges.confirmBeforeBreak(nodes, tree)).isEqualTo(EdgeConfirm.NONE)
        assertThat(SchemaEdges.confirmBeforeBreak(nodes, pipe)).isEqualTo(EdgeConfirm.PIPE)
        val next = SchemaEdges.afterPipeBreak(nodes, "leaf")
        assertThat(next.single().id).isEqualTo("leaf")
        assertThat(next.single().pipeName).isEmpty()
        assertThat(next.single().parentId).isNull()
    }

    private fun toneOf(nodes: List<RuleNodeRecord>, kind: SchemaEdgeKind, fromId: String, toId: String): RouteTone =
        SchemaEdges.tone(nodes, SchemaEdge(kind, fromId, toId))

    private fun rule(
        id: String,
        parentId: String? = null,
        domains: List<String> = emptyList(),
        pipeName: String = "",
        action: String = "proxy",
    ): RuleNodeRecord = RuleNodeRecord(
        id = id,
        profileId = "p",
        parentId = parentId,
        enabled = true,
        sortIndex = 0,
        action = action,
        apps = emptyList(),
        domains = domains,
        domainSuffixes = emptyList(),
        ipCidrs = emptyList(),
        geoip = emptyList(),
        pipeName = pipeName,
    )
}
