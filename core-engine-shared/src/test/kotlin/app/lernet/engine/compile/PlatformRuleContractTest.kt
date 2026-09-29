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

    @Test fun windowsProcessRuleIsRejectedOnAndroid() {
        val assembled = ConfigAssembler.assemble(outbound, route(RuleMatch(processes = listOf("browser.exe"))),
            RunMode.FULL_VPN, "info", platform = EnginePlatform.ANDROID)
        assertThat(assembled.isValid).isFalse()
        assertThat(assembled.errors.single()).contains("Windows-")
    }

    @Test fun androidPackageRuleIsRejectedOnWindows() {
        val assembled = ConfigAssembler.assemble(outbound, route(RuleMatch(apps = listOf("com.example.app"))),
            RunMode.FULL_VPN, "info", platform = EnginePlatform.WINDOWS)
        assertThat(assembled.isValid).isFalse()
        assertThat(assembled.errors.single()).contains("Android-")
    }
}
