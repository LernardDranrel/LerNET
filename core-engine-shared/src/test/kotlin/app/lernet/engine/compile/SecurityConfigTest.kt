package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Security-relevant parts of the generated sing-box configuration, for both platforms. */
class SecurityConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun windowsVpnUsesStrictRouteByDefault() {
        assertThat(tun(EnginePlatform.WINDOWS, EngineDefaults())["strict_route"]!!.jsonPrimitive.content).isEqualTo("true")
    }

    @Test
    fun windowsStrictRouteCanBeSwitchedOff() {
        val tun = tun(EnginePlatform.WINDOWS, EngineDefaults(strictRoute = false))
        assertThat(tun["strict_route"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test
    fun androidNeverEmitsStrictRoute() {
        assertThat(tun(EnginePlatform.ANDROID, EngineDefaults(strictRoute = true))["strict_route"]!!.jsonPrimitive.content)
            .isEqualTo("false")
    }

    private fun tun(platform: EnginePlatform, defaults: EngineDefaults): JsonObject =
        inbounds(assemble(RunMode.FULL_VPN, platform, defaults)).first { it["type"]!!.jsonPrimitive.content == "tun" }

    internal fun assemble(
        mode: RunMode,
        platform: EnginePlatform,
        defaults: EngineDefaults = EngineDefaults(),
    ): AssembledConfig = ConfigAssembler.assemble(
        outbound(), catchAllProxy(), mode, "warn", defaults = defaults, platform = platform,
    ).also { assertThat(it.errors).isEmpty() }

    internal fun inbounds(config: AssembledConfig): List<JsonObject> =
        json.parseToJsonElement(config.json).jsonObject["inbounds"]!!.jsonArray.map { it.jsonObject }

    internal fun rules(config: AssembledConfig): List<JsonObject> =
        json.parseToJsonElement(config.json).jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    private fun outbound() = NormalizedOutbound(
        id = "out-1",
        tag = "proxy",
        type = "shadowsocks",
        singBoxJson = """{"type":"shadowsocks","tag":"proxy","server":"10.200.1.2","server_port":8388,""" +
            """"method":"aes-128-gcm","password":"evidence"}""",
    )

    private fun catchAllProxy() = RouteCompiler.compile(
        listOf(RuleNode("else", null, true, 0, RuleMatch(), RouteAction.PROXY)),
    )
}
