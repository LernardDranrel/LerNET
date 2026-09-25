package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.CompiledRoute
import app.lernet.routing.CompiledRule
import app.lernet.routing.MatchKind
import app.lernet.routing.RouteAction
import app.lernet.routing.RuleMatch
import app.lernet.routing.Specificity
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class PipeAffinityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val source = """
        {"type":"vless","tag":"proxy","server":"203.0.113.42","server_port":443,
        "uuid":"11111111-1111-1111-1111-111111111111",
        "tls":{"enabled":true,"server_name":"store.steampowered.com"},
        "transport":{"type":"xhttp","mode":"stream-one","path":"/xhttp"}}
    """.trimIndent().replace("\n", "")

    @Test
    fun videoPipeIsSeparatePoolSameDisguise() {
        val assembled = ConfigAssembler.assemble(
            NormalizedOutbound("o", "proxy", "vless", source),
            CompiledRoute(
                rules = listOf(
                    rule("yt", RuleMatch(domains = listOf("www.youtube.com")), "video"),
                    rule("tw", RuleMatch(domains = listOf("www.twitch.tv")), "chat"),
                ),
                finalAction = RouteAction.PROXY,
                errors = emptyList(),
            ),
            RunMode.FULL_VPN,
            "info",
        )
        assertThat(assembled.isValid).isTrue()
        val root = json.parseToJsonElement(assembled.json).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val vless = outbounds.filter { it["type"]!!.jsonPrimitive.content == "vless" }
        assertThat(vless.map { it["tag"]!!.jsonPrimitive.content })
            .containsExactly("proxy", "pipe-video", "pipe-chat")
            .inOrder()
        val names = vless.map { it["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content }
        assertThat(names).containsExactly(
            "store.steampowered.com",
            "store.steampowered.com",
            "store.steampowered.com",
        )
        vless.forEach { outbound ->
            val mux = outbound["transport"]!!.jsonObject["xmux"]!!.jsonObject
            assertThat(mux["max_concurrency"]!!.jsonPrimitive.content).isEqualTo(XhttpMode.MUX_CONCURRENCY)
        }
        val rules = root["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val youtube = rules.first { it["domain"]?.toString()?.contains("youtube") == true }
        assertThat(youtube["outbound"]!!.jsonPrimitive.content).isEqualTo("pipe-video")
        assertThat(root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content).isEqualTo("proxy")
    }

    private fun rule(id: String, match: RuleMatch, pipe: String) = CompiledRule(
        nodeId = id,
        specificity = Specificity(MatchKind.EXACT_DOMAIN, 0, 0),
        match = match,
        action = RouteAction.PROXY,
        pipeName = pipe,
    )
}
