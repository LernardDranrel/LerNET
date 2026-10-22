package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Audit R3: «Приложения через VPN» / «Программы через VPN» without building a rule by hand. */
class BypassAppsTest {
    private val outbound = NormalizedOutbound("id", "proxy", "vless",
        """{"type":"vless","server":"example.com","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"}""")
    private val route = RouteCompiler.compile(listOf(RuleNode("otherwise", null, true, 0, RuleMatch(), RouteAction.PROXY)))

    private fun assemble(platform: EnginePlatform, mode: RunMode = RunMode.FULL_VPN, apps: List<String>) =
        ConfigAssembler.assemble(outbound, route, mode, "info", platform = platform,
            defaults = EngineDefaults(bypassApps = apps))

    @Test fun androidTakesTheAppsOutOfTheTun() {
        val assembled = assemble(EnginePlatform.ANDROID, apps = listOf("org.bank.app", " org.bank.app ", "com.game"))
        assertThat(assembled.isValid).isTrue()
        val tun = Json.parseToJsonElement(assembled.json).jsonObject["inbounds"]!!.jsonArray.first().jsonObject
        assertThat(tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("com.game", "org.bank.app").inOrder()
        assertThat(assembled.json).doesNotContain("process_name")
    }

    @Test fun windowsSendsTheProgramsDirectBeforeUserRules() {
        val assembled = assemble(EnginePlatform.WINDOWS, apps = listOf("Steam.exe"))
        val rules = Json.parseToJsonElement(assembled.json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val bypass = rules.indexOfFirst { "process_name" in it }
        assertThat(bypass).isAtLeast(0)
        assertThat(rules[bypass]["outbound"]!!.jsonPrimitive.content).isEqualTo("direct")
        // DNS hijack still comes first, so the program's DNS questions are answered by LerNET as before.
        assertThat(rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "hijack-dns" }).isLessThan(bypass)
        assertThat(assembled.json).doesNotContain("exclude_package")
    }

    @Test fun nothingChangesWithoutAppsOrInProxyMode() {
        assertThat(assemble(EnginePlatform.ANDROID, apps = emptyList()).json).doesNotContain("exclude_package")
        assertThat(assemble(EnginePlatform.WINDOWS, RunMode.PROXY, listOf("Steam.exe")).json).doesNotContain("Steam.exe")
    }
}
