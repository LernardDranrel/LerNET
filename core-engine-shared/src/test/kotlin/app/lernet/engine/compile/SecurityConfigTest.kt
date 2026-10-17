package app.lernet.engine.compile

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.net.LocalProxyAccess
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

    @Test
    fun proxyModeListenerRequiresLoginAndPasswordOnBothPlatforms() {
        for (platform in EnginePlatform.entries) {
            val config = assemble(RunMode.PROXY, platform)
            val mixed = inbounds(config).first { it["tag"]!!.jsonPrimitive.content == LocalListeners.USER_PROXY_TAG }
            assertThat(mixed["listen"]!!.jsonPrimitive.content).isEqualTo("127.0.0.1")
            assertThat(mixed["listen_port"]!!.jsonPrimitive.content).isEqualTo("2080")
            val user = mixed["users"]!!.jsonArray.single().jsonObject
            val access = checkNotNull(config.userProxy)
            assertThat(user["username"]!!.jsonPrimitive.content).isEqualTo(access.username)
            assertThat(user["password"]!!.jsonPrimitive.content).isEqualTo(access.password)
            assertThat(LocalListeners.userProxy(config.json)).isEqualTo(access)
        }
    }

    @Test
    fun savedProxyPairIsUsedAsIs() {
        val own = LocalProxyAccess.forUserProxy("me", "s3cret!")
        val config = ConfigAssembler.assemble(outbound(), catchAllProxy(), RunMode.PROXY, "warn",
            platform = EnginePlatform.WINDOWS, userProxy = own)
        assertThat(config.userProxy).isEqualTo(LocalProxyAccess(2080, "me", "s3cret!"))
        assertThat(LocalListeners.userProxy(config.json)).isEqualTo(own)
    }

    @Test
    fun vpnModeHasNoUserProxyListener() {
        for (platform in EnginePlatform.entries) {
            val config = assemble(RunMode.FULL_VPN, platform)
            assertThat(config.userProxy).isNull()
            assertThat(inbounds(config).none { it["tag"]!!.jsonPrimitive.content == LocalListeners.USER_PROXY_TAG }).isTrue()
        }
    }

    @Test
    fun noLocalListenerWithoutPasswordInAnyModeOrPlatform() {
        val app = LocalProxyAccess.random(ConfigAssembler.APP_PROXY_PORT)
        for (platform in EnginePlatform.entries) for (mode in RunMode.entries) {
            val config = ConfigAssembler.assemble(outbound(), catchAllProxy(), mode, "warn", platform = platform, appProxy = app)
            assertThat(config.errors).isEmpty()
            inbounds(config).filter { it["type"]!!.jsonPrimitive.content != "tun" }.forEach { inbound ->
                assertThat(inbound["listen"]!!.jsonPrimitive.content).isEqualTo("127.0.0.1")
                val users = inbound["users"]?.jsonArray
                assertThat(users).isNotNull()
                assertThat(users!!).isNotEmpty()
                users.forEach { user ->
                    assertThat(user.jsonObject["password"]!!.jsonPrimitive.content).isNotEmpty()
                }
            }
        }
    }

    @Test
    fun appOwnTrafficGoesToVpnServerBeforeAnyUserRule() {
        val app = LocalProxyAccess.random(ConfigAssembler.APP_PROXY_PORT)
        for (platform in EnginePlatform.entries) for (mode in RunMode.entries) {
            val config = ConfigAssembler.assemble(outbound(), catchAllProxy(), mode, "warn", platform = platform, appProxy = app)
            val first = rules(config).first()
            assertThat(first["inbound"]!!.jsonArray.single().jsonPrimitive.content).isEqualTo(LocalListeners.APP_PROXY_TAG)
            assertThat(first["outbound"]!!.jsonPrimitive.content).isEqualTo("proxy")
            assertThat(first.keys).containsExactly("inbound", "outbound")
            assertThat(config.appProxy).isEqualTo(app)
            assertThat(LocalListeners.appProxy(config.json)).isEqualTo(app)
        }
    }

    @Test
    fun withoutAppProxyThereIsNoInternalListenerAndNoOldOpenProbe() {
        val config = assemble(RunMode.FULL_VPN, EnginePlatform.ANDROID)
        assertThat(inbounds(config).map { it["tag"]!!.jsonPrimitive.content }).containsNoneOf("app-in", "probe-in")
        assertThat(config.json).doesNotContain("probe-in")
    }

    @Test
    fun freshConfigsAreNeverTakenForOldOpenOnes() {
        val app = LocalProxyAccess.random(ConfigAssembler.APP_PROXY_PORT)
        for (platform in EnginePlatform.entries) for (mode in RunMode.entries) {
            val config = ConfigAssembler.assemble(outbound(), catchAllProxy(), mode, "warn", platform = platform, appProxy = app)
            assertThat(LocalListeners.hasOpenListener(config.json)).isFalse()
        }
    }

    @Test
    fun sessionSavedByOldVersionIsRecognisedAsOpen() {
        val openProxy = """{"inbounds":[{"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2080}]}"""
        val openProbe = """{"inbounds":[{"type":"tun","tag":"tun-in"},""" +
            """{"type":"mixed","tag":"probe-in","listen":"127.0.0.1","listen_port":2081,"users":[{"username":"a","password":"b"}]}]}"""
        val emptyUsers = """{"inbounds":[{"type":"socks","tag":"x","listen":"127.0.0.1","listen_port":1,"users":[]}]}"""
        assertThat(LocalListeners.hasOpenListener(openProxy)).isTrue()
        assertThat(LocalListeners.hasOpenListener(openProbe)).isTrue()
        assertThat(LocalListeners.hasOpenListener(emptyUsers)).isTrue()
        assertThat(LocalListeners.hasOpenListener("{not json")).isTrue()
        assertThat(LocalListeners.hasOpenListener("""{"inbounds":[{"type":"tun","tag":"tun-in"}]}""")).isFalse()
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
