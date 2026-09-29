package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PipeTagContractTest {
    @Test fun namedChannelsCannotCollideWithImportedOutboundTagsOnEitherPlatform() {
        val source = NormalizedOutbound("o", "pipe-video", "vless",
            """{"type":"vless","server":"example.org","server_port":443}""")
        val route = RouteCompiler.compile(listOf(
            RuleNode("a", null, true, 0, RuleMatch(domains = listOf("a.example")), RouteAction.PROXY, pipeName = "video"),
            RuleNode("b", null, true, 1, RuleMatch(domains = listOf("b.example")), RouteAction.PROXY, pipeName = " video "),
            RuleNode("else", null, true, 2, RuleMatch(), RouteAction.PROXY),
        ))
        EnginePlatform.entries.forEach { platform ->
            val assembled = ConfigAssembler.assemble(source, route, RunMode.FULL_VPN, "info", platform = platform)
            assertTrue(assembled.errors.toString(), assembled.isValid)
            val root = Json.parseToJsonElement(assembled.json).jsonObject
            val tags = root.getValue("outbounds").jsonArray.map { it.jsonObject.getValue("tag").jsonPrimitive.content }
            assertEquals(3, tags.size)
            assertEquals(tags.size, tags.toSet().size)
            val rules = root.getValue("route").jsonObject.getValue("rules").jsonArray.map { it.jsonObject }
                .filter { it["domain"]?.toString()?.contains(".example") == true }
            assertEquals(2, rules.size)
            assertEquals(rules.first()["outbound"], rules.last()["outbound"])
            assertNotEquals(JsonPrimitive(source.tag), rules.first()["outbound"])
        }
    }
}
