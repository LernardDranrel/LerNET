package app.lernet.desktop

import app.lernet.config.model.DnsPolicy
import app.lernet.engine.RunMode
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.EnginePlatform
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class OutboundProbeResult(val latencyMs: Long? = null, val message: String = "")

/**
 * Preflight uses a short-lived sing-box process with a localhost-only inbound.
 * Clash's delay endpoint sends an HTTP request via the real outbound, including
 * its TLS/Reality/XHTTP handshake. It never creates a TUN or changes OS routes.
 */
object OutboundProbe {
    private val json = Json { ignoreUnknownKeys = true }

    fun check(profile: StoredProfile, saved: StoredState, executable: Path, workDirectory: Path,
        timeoutMs: Int = 6_000): OutboundProbeResult {
        val outbound = profile.selectedOutbound ?: return OutboundProbeResult(message = "Нет выбранного сервера")
        val compiled = RouteCompiler.compile(listOf(
            RuleNode("probe-else", null, true, 0, RuleMatch(), RouteAction.PROXY),
        ))
        val policy = runCatching {
            DnsPolicy.valueOf(if (profile.dnsPolicy == "SYSTEM") saved.defaultDnsPolicy else profile.dnsPolicy)
        }.getOrDefault(DnsPolicy.UNDERLAY)
        val assembled = ConfigAssembler.assemble(
            outbound = outbound,
            compiledRoute = compiled,
            mode = RunMode.PROXY,
            logLevel = "error",
            dnsJson = profile.dnsJson,
            dnsPolicy = policy,
            defaults = EngineDefaults(saved.tunMtu, saved.xmuxConcurrency, saved.directDnsServer),
            platform = EnginePlatform.WINDOWS,
        )
        if (!assembled.isValid) return OutboundProbeResult(message = assembled.errors.joinToString("; ").take(180))
        val api = LocalCoreApi()
        val inboundPort = ServerSocket(0).use { it.localPort }
        val config = api.inject(localInbound(assembled.json, inboundPort))
        val directory = runCatching {
            Files.createDirectories(workDirectory)
            Files.createTempDirectory(workDirectory, "preflight-")
        }.getOrElse { return OutboundProbeResult(message = "Нет доступа к папке данных для временной проверки") }
        val configFile = directory.resolve("probe.json")
        val logFile = directory.resolve("probe.log")
        var process: Process? = null
        return try {
            Files.writeString(configFile, config)
            val running = ProcessBuilder(executable.toAbsolutePath().toString(), "run", "-c", configFile.toString())
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start()
            process = running
            awaitReady(running, api.port, timeoutMs.coerceAtMost(5_000))
            val latency = api.delay(assembled.proxyTag, saved.healthUrl, timeoutMs)
            OutboundProbeResult(latencyMs = latency, message = "HTTP через выбранный узел")
        } catch (error: Exception) {
            OutboundProbeResult(message = when (error) {
                is InterruptedException -> "Проверка прервана"
                is java.nio.file.FileSystemException -> "Нет доступа к файлам временной проверки"
                else -> error.message?.take(180) ?: "Узел не ответил через VPN"
            })
        } finally {
            process?.let { running ->
                running.destroy()
                if (runCatching { running.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false).not()) {
                    running.destroyForcibly()
                    runCatching { running.waitFor(2, TimeUnit.SECONDS) }
                }
            }
            deleteWhenReleased(configFile)
            deleteWhenReleased(logFile)
            deleteWhenReleased(directory)
        }
    }

    private fun localInbound(raw: String, port: Int): String {
        val root = json.parseToJsonElement(raw).jsonObject
        val inbound = (root["inbounds"] as JsonArray).first().jsonObject
        val local = JsonObject(inbound.toMutableMap().apply { put("listen_port", JsonPrimitive(port)) })
        val route = (root["route"] as JsonObject).toMutableMap().apply {
            // The temporary check has no TUN route to escape. Binding its
            // outbound to a physical NIC breaks loopback checks on Windows.
            put("auto_detect_interface", JsonPrimitive(false))
        }
        return JsonObject(root.toMutableMap().apply {
            put("inbounds", JsonArray(listOf(local)))
            put("route", JsonObject(route))
        }).toString()
    }

    private fun awaitReady(process: Process, port: Int, timeoutMs: Int) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) error("Временное ядро завершилось до проверки")
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
            }.isSuccess
            if (open) return
            Thread.sleep(100)
        }
        error("Ядро не открыло порт проверки")
    }

    private fun deleteWhenReleased(path: Path) {
        repeat(15) {
            if (runCatching { Files.deleteIfExists(path) }.isSuccess) return
            runCatching { Thread.sleep(100) }
        }
    }
}
