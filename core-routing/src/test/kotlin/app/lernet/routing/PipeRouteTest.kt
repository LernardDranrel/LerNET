package app.lernet.routing

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PipeRouteTest {
    @Test
    fun namedPipeSelectsItsOutboundAndCatchAllStaysDefault() {
        val video = node("yt", RuleMatch(domains = listOf("www.youtube.com")), "video", sort = 0)
        val rest = node("all", RuleMatch(), "", sort = 1)
        val compiled = RouteCompiler.compile(listOf(rest, video))
        val rules = RouteCompiler.toSingBoxRules(compiled, "proxy", mapOf("video" to "pipe-video"))
        assertThat(compiled.finalAction).isEqualTo(RouteAction.PROXY)
        assertThat(rules).hasSize(1)
        assertThat(rules.single()["outbound"]!!.jsonPrimitive.content).isEqualTo("pipe-video")
        assertThat(rules.single()["domain"]!!.toString()).contains("www.youtube.com")
    }

    @Test
    fun catchAllPipeNameDoesNotChangeFinal() {
        val rest = RuleNode(
            id = "all",
            parentId = null,
            enabled = true,
            sortIndex = 0,
            match = RuleMatch(),
            action = RouteAction.PROXY,
            pipeName = "video",
        )
        val compiled = RouteCompiler.compile(listOf(rest))
        assertThat(compiled.isValid).isTrue()
        assertThat(compiled.rules).hasSize(1)
        assertThat(compiled.rules.single().pipeName).isEqualTo("video")
        val rules = RouteCompiler.toSingBoxRules(compiled, "proxy", mapOf("video" to "pipe-video"))
        assertThat(rules.single()["outbound"]!!.jsonPrimitive.content).isEqualTo("pipe-video")
    }

    @Test
    fun reparentCycleIsRejected() {
        val parents = mapOf("a" to null, "b" to "a", "c" to "b")
        assertThat(RouteTree.wouldCycle(parents, "a", "c")).isTrue()
        assertThat(RouteTree.wouldCycle(parents, "c", null)).isFalse()
    }

    @Test
    fun mixedKindsAreVisible() {
        val kinds = RouteTree.filledKinds(
            RuleMatch(domains = listOf("youtube.com"), apps = listOf("com.google.android.youtube")),
        )
        assertThat(kinds).containsExactly(MatchKind.EXACT_DOMAIN, MatchKind.APP).inOrder()
    }

    private fun node(id: String, match: RuleMatch, pipe: String, sort: Int = 0) = RuleNode(
        id = id,
        parentId = null,
        enabled = true,
        sortIndex = sort,
        match = match,
        action = RouteAction.PROXY,
        pipeName = pipe,
    )
}
