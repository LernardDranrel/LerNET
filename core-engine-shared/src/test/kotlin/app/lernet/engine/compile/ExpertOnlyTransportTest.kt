package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertOnlyTransportTest {
    @Test
    fun verifiedInterfaceIsNeverSilentlyConsumedBySimpleSingBox() {
        val outbound = NormalizedOutbound(
            "corp", "proxy", "direct",
            """{"type":"direct","lernet_interface":{"guid":"134ab342-a65b-41a3-b2a4-daf023fcd995","name":"Company VPN","index":17}}"""
        )
        val route = RouteCompiler.compile(listOf(RuleNode("root", null, true, 0, RuleMatch(), RouteAction.PROXY)))
        assertTrue(route.errors.toString(), route.isValid)
        for (platform in EnginePlatform.entries) {
            val result = ConfigAssembler.assemble(outbound, route, RunMode.FULL_VPN, "info", platform = platform)
            assertFalse(result.isValid)
            assertTrue(result.errors.toString(), result.errors.single().contains("экспертном режиме"))
            assertTrue(result.json.isEmpty())
        }
    }
}
