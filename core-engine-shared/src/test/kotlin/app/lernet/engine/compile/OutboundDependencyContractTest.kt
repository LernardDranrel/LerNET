package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.*
import org.junit.Assert.*
import org.junit.Test

class OutboundDependencyContractTest {
    private val fallback = RouteCompiler.compile(listOf(RuleNode("else", null, true, 0, RuleMatch(), RouteAction.PROXY)))
    private fun assemble(tag: String = "proxy", extra: String = "") = ConfigAssembler.assemble(
        NormalizedOutbound("o", tag, "vless", """{"type":"vless","server":"example.org"$extra}"""),
        fallback, RunMode.PROXY, "info")

    @Test fun missingSelectedOutboundDependenciesAreReportedBeforeCoreStartup() {
        assertTrue(assemble(extra = """, "detour":"other-node"""").errors.any { it.contains("detour[other-node] not found") })
        assertTrue(assemble(extra = """, "domain_resolver":"missing-dns"""").errors.any { it.contains("domain_resolver[missing-dns] not found") })
        assertTrue(assemble(extra = """, "detour":"proxy"""").errors.any { it.contains("detour cycle") })
        assertTrue(assemble(tag = "direct").errors.any { it.contains("duplicate outbound tag[direct]") })
        assertTrue(assemble().isValid)
    }
}
