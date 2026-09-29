package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlatformRuleContractTest {
    private val outbound = NormalizedOutbound("id", "proxy", "vless",
        """{"type":"vless","server":"example.com","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"}""")

    private fun route(match: RuleMatch) = RouteCompiler.compile(listOf(
        RuleNode("specific", null, true, 0, match, RouteAction.DIRECT),
        RuleNode("otherwise", null, true, 1, RuleMatch(), RouteAction.PROXY),
    ))

    @Test fun windowsProcessRuleIsInactiveOnAndroid() {
        val assembled = ConfigAssembler.assemble(outbound, route(RuleMatch(processes = listOf("browser.exe"))),
            RunMode.FULL_VPN, "info", platform = EnginePlatform.ANDROID)
        assertThat(assembled.isValid).isTrue()
        assertThat(assembled.json).doesNotContain("browser.exe")
        assertThat(assembled.json).doesNotContain("process_name")
        assertThat(assembled.notes).isNotEmpty()
    }

    @Test fun androidPackageRuleIsInactiveOnWindows() {
        val assembled = ConfigAssembler.assemble(outbound, route(RuleMatch(apps = listOf("com.example.app"))),
            RunMode.FULL_VPN, "info", platform = EnginePlatform.WINDOWS)
        assertThat(assembled.isValid).isTrue()
        assertThat(assembled.json).doesNotContain("com.example.app")
        assertThat(assembled.json).doesNotContain("package_name")
        assertThat(assembled.notes).isNotEmpty()
    }
}
