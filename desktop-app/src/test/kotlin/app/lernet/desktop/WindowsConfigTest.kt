package app.lernet.desktop

import app.lernet.config.model.NormalizedOutbound
import app.lernet.engine.RunMode
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.compile.EnginePlatform
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.net.ServerSocket
import java.net.Socket
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class WindowsConfigTest {
    @Test
    fun processDiscoveryIsEnabledOnlyForTun() {
        val api = LocalCoreApi()
        val tun = Json.parseToJsonElement(api.inject("""{"inbounds":[{"type":"tun"}],"route":{}}""")).jsonObject
        val proxy = Json.parseToJsonElement(api.inject("""{"inbounds":[{"type":"mixed"}],"route":{}}""")).jsonObject
        assertThat(tun["route"]!!.jsonObject["find_process"]!!.jsonPrimitive.content).isEqualTo("true")
        assertThat(proxy["route"]!!.jsonObject.containsKey("find_process")).isFalse()
    }

    @Test
    fun delayTimeoutKeepsApiReasonAndIsWrittenToJournal() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/proxies/probe/delay") { exchange ->
            val body = """{"message":"Request timeout"}""".toByteArray()
            exchange.sendResponseHeaders(504, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val directory = Files.createTempDirectory("lernet-preflight-journal-test")
        try {
            val failure = runCatching {
                LocalCoreApi(port = server.address.port).delay("probe", "https://example.com")
            }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(failure!!.message).contains("HTTP 504 · истёк лимит 30000 мс")
            assertThat(failure.message).contains("Request timeout")

            val tunnel = WindowsBoxProcess(directory)
            tunnel.logDiagnostic("Профиль «test»: ${failure.message}")
            assertThat(Files.readString(directory.resolve("session.log"))).contains("Request timeout")
            assertThat(tunnel.state.value.logs.last()).contains("HTTP 504")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun windowsHealthChecksTheApplicationPath() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/health"
        try {
            assertThat(WindowsTunnelHealth.check(url, RunMode.FULL_VPN, routeCheck = { null }).latencyMs).isNotNull()
        } finally {
            server.stop(0)
        }
        assertThat(WindowsTunnelHealth.check(url, RunMode.FULL_VPN, 1_000, routeCheck = { null }).latencyMs).isNull()
    }

    @Test
    fun competingVpnRouteCannotPassTheWindowsHealthCheck() {
        val result = WindowsTunnelHealth.check("https://example.com", RunMode.FULL_VPN,
            routeCheck = { "Маршрут Windows идёт через другой VPN" })
        assertThat(result.latencyMs).isNull()
        assertThat(result.routeConflict).isTrue()
        assertThat(result.error).contains("другой VPN")
        assertThat(WindowsRouteInspector.isLerNetInterface("LerNET")).isTrue()
        assertThat(WindowsRouteInspector.isLerNetInterface("TampleVPN")).isFalse()
    }

    @Test
    fun fullVpnFallsBackToProxyWithoutElevation() {
        assertThat(DesktopRunMode.effective("FULL_VPN", elevated = false)).isEqualTo(RunMode.PROXY)
        assertThat(DesktopRunMode.effective("FULL_VPN", elevated = true)).isEqualTo(RunMode.FULL_VPN)
        assertThat(DesktopRunMode.effective("PROXY", elevated = true)).isEqualTo(RunMode.PROXY)
    }

    @Test
    fun windowsRequestsElevationForVpnDefaultAndHonorsProxyChoice() {
        assertThat(StoredState().mode).isEqualTo(RunMode.FULL_VPN.name)
        assertThat(DesktopStartup.needsElevation(StoredState(), elevated = false)).isTrue()
        assertThat(DesktopStartup.needsElevation(StoredState(), elevated = true)).isFalse()
        assertThat(DesktopStartup.needsElevation(StoredState(mode = "PROXY"), elevated = false)).isFalse()
        val profile = StoredProfile("profile", "proxy", "test", emptyList(), "", modeOverride = "PROXY")
        assertThat(DesktopStartup.needsElevation(StoredState(
            profiles = listOf(profile), selectedProfileId = profile.id,
        ), elevated = false)).isFalse()
    }

    @Test
    fun vpnChoiceIsSavedBeforeElevationRelaunch() {
        val directory = java.nio.file.Files.createTempDirectory("lernet-elevation-choice")
        val profile = StoredProfile("profile", "VPN", "test", emptyList(), "", modeOverride = "PROXY")
        val store = DesktopStore(directory)
        store.save(StoredState(profiles = listOf(profile), selectedProfileId = profile.id, mode = "PROXY"))
        val controller = DesktopController(store)
        try {
            assertThat(controller.prepareVpnElevation(profile)).isTrue()
            val persisted = DesktopStore(directory).load()
            assertThat(persisted.profiles.single().modeOverride).isEqualTo(RunMode.FULL_VPN.name)
            assertThat(DesktopStartup.needsElevation(persisted, elevated = false)).isTrue()
        } finally {
            controller.close()
        }
    }

    @Test
    fun tunPermissionFailureIsDistinguishedFromEndpointFailure() {
        val denied = "FATAL[0000] start service: start inbound/tun[tun-in]: configure tun interface: Access is denied."
        assertThat(WindowsBoxProcess.isTunPermissionFailure(denied)).isTrue()
        assertThat(WindowsBoxProcess.isTunPermissionFailure("FATAL[0000] dial tcp: connection timed out")).isFalse()
    }

    @Test
    fun preflightMeasuresHttpThroughOutboundBeforeTunStarts() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/generate_204") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        val directory = Files.createTempDirectory("lernet-preflight-test")
        try {
            val binary = BundledCore.install(directory)
            val profile = StoredProfile(
                id = "local", name = "Local direct", source = "test",
                outbounds = listOf(StoredOutbound("out", "probe", "direct", """{"type":"direct","tag":"probe"}""")),
                selectedOutboundId = "out",
            )
            val saved = StoredState(healthUrl = "http://127.0.0.1:${server.address.port}/generate_204")
            val result = OutboundProbe.check(profile, saved, binary, directory, timeoutMs = 5_000)
            assertThat(result.message).isEqualTo("HTTP через выбранный узел")
            assertThat(result.latencyMs).isNotNull()
            assertThat(Files.list(directory).use { stream -> stream.noneMatch { it.fileName.toString().startsWith("preflight-") } }).isTrue()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun parsesWindowsTracerouteWithoutInventingSilentHop() {
        val first = WindowsTracer.parseLine("  1    <1 мс     1 мс     1 мс  192.168.1.1")
        val silent = WindowsTracer.parseLine("  2     *        *        *     Превышен интервал ожидания")
        assertThat(first?.ip).isEqualTo("192.168.1.1")
        assertThat(first?.rttMs).isEqualTo(1)
        assertThat(silent?.number).isEqualTo(2)
        assertThat(silent?.ip).isNull()
    }

    @Test
    fun readsLiveConnectionsApiFromBundledCore() {
        val directory = Files.createTempDirectory("lernet-windows-api")
        val binary = BundledCore.install(directory)
        val port = ServerSocket(0).use { it.localPort }
        val api = LocalCoreApi()
        val config = api.inject("""{"log":{"level":"warn"},"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","listen_port":$port}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}""")
        val tunnel = WindowsBoxProcess(directory)
        try {
            tunnel.start(binary, config)
            val until = System.currentTimeMillis() + 10_000
            while (tunnel.state.value.status != TunnelStatus.RUNNING &&
                tunnel.state.value.status != TunnelStatus.FAILED && System.currentTimeMillis() < until) Thread.sleep(100)
            assertThat(tunnel.state.value.status).isEqualTo(TunnelStatus.RUNNING)
            assertThat(api.poll().uploadTotal).isAtLeast(0)
        } finally {
            tunnel.close()
        }
    }

    @Test
    fun restoresProfilesFromBackupWithoutDiscardingCorruptFile() {
        val directory = Files.createTempDirectory("lernet-windows-store")
        val store = DesktopStore(directory)
        store.save(StoredState(groups = listOf(StoredGroup("one", "Семья"))))
        store.save(StoredState(groups = listOf(StoredGroup("two", "Работа"))))
        Files.writeString(directory.resolve("state.json"), "{broken")

        val reopened = DesktopStore(directory)
        assertThat(reopened.load().groups.single().name).isEqualTo("Семья")
        assertThat(reopened.recoveredFromBackup).isTrue()
        assertThat(Files.readString(directory.resolve("state.json"))).contains("Семья")
        assertThat(Files.list(directory).use { stream -> stream.anyMatch { it.fileName.toString().startsWith("state.json.corrupt-") } }).isTrue()
    }

    @Test
    fun launchesLocalProxyAndStops() {
        val directory = Files.createTempDirectory("lernet-windows-proxy")
        val binary = BundledCore.install(directory)
        val port = ServerSocket(0).use { it.localPort }
        val config = """{"log":{"level":"warn"},"inbounds":[{"type":"mixed","tag":"in","listen":"127.0.0.1","listen_port":$port}],"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}"""
        val tunnel = WindowsBoxProcess(directory)
        try {
            tunnel.start(binary, config)
            val until = System.currentTimeMillis() + 10_000
            while (tunnel.state.value.status != TunnelStatus.RUNNING &&
                tunnel.state.value.status != TunnelStatus.FAILED &&
                System.currentTimeMillis() < until
            ) {
                Thread.sleep(100)
            }
            assertThat(tunnel.state.value.status).isEqualTo(TunnelStatus.RUNNING)
            Socket("127.0.0.1", port).use { assertThat(it.isConnected).isTrue() }
        } finally {
            tunnel.close()
        }
        assertThat(tunnel.state.value.status).isEqualTo(TunnelStatus.STOPPED)
    }

    @Test
    fun windowsTunUsesWindowsAdapterAndProcessRules() {
        val rules = RouteCompiler.compile(
            listOf(
                RuleNode("browser", null, true, 0, RuleMatch(processes = listOf("browser.exe")), RouteAction.DIRECT),
                RuleNode("else", null, true, 1, RuleMatch(), RouteAction.PROXY),
            ),
        )
        assertThat(rules.errors).isEmpty()
        val config = ConfigAssembler.assemble(
            NormalizedOutbound(
                "test", "proxy", "vless",
                """{"type":"vless","tag":"proxy","server":"example.com","server_port":443,"uuid":"11111111-1111-1111-1111-111111111111"}""",
            ),
            rules, RunMode.FULL_VPN, "warn", platform = EnginePlatform.WINDOWS,
        )
        assertThat(config.errors).isEmpty()
        val root = Json.parseToJsonElement(config.json).jsonObject
        val inbound = root.getValue("inbounds").jsonArray.first().jsonObject
        assertThat(inbound.getValue("interface_name").toString()).isEqualTo("\"LerNET\"")
        assertThat(inbound.getValue("address").jsonArray.map { it.toString() })
            .contains("\"fdfe:dcba:9876::1/126\"")
        assertThat(config.json).contains("process_name")
        assertThat(config.json).contains("browser.exe")

        val directory = Files.createTempDirectory("lernet-windows-check")
        val binary = BundledCore.install(directory)
        val configFile = directory.resolve("config.json")
        Files.writeString(configFile, config.json)
        val process = ProcessBuilder(binary.toString(), "check", "-c", configFile.toString())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue()
        assertThat(process.exitValue()).isEqualTo(0)
        assertThat(output).doesNotContain("FATAL")
    }
}
