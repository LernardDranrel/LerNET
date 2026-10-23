package app.lernet.desktop

import app.lernet.engine.compile.OutboundPatch
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

data class ProbeResult(
    val latencyMs: Long? = null,
    val message: String = "",
    val checking: Boolean = false,
    val tunnelLatencyMs: Long? = null,
    val tunnelMessage: String = "",
    val serverProtocol: String = "TCP",
    /** The check waits for the handshake budget; shown as BudgetNotice instead of a silent «Проверяем…». */
    val budgetWaitMs: Long? = null,
) {
    /** The path through the VPN was measured and failed — not «waiting» and not «checked on switching» (LinkCheck). */
    val tunnelFailed: Boolean
        get() = tunnelLatencyMs == null && !checking && tunnelMessage.isNotBlank() && tunnelMessage != WAITING &&
            tunnelMessage != app.lernet.engine.policy.LinkCheck.notMeasured(app.lernet.engine.policy.LinkCheck.FullPath.ON_SWITCH) &&
            tunnelMessage != app.lernet.engine.policy.LinkCheck.notMeasured(app.lernet.engine.policy.LinkCheck.FullPath.ON_CONNECT)

    companion object {
        const val WAITING = "Ожидает проверки"
    }
}

/** Direct TCP/TLS handshake to the endpoint; the HTTP channel is checked separately. */
object EndpointProbe {
    private val json = Json { ignoreUnknownKeys = true }

    fun protocol(profile: StoredProfile): String {
        val tls = profile.selectedOutbound?.singBoxJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonObject["tls"] as? JsonObject }.getOrNull()
        }
        return if (tls?.get("enabled")?.jsonPrimitive?.booleanOrNull == true) "TLS" else "TCP"
    }

    /** [tlsHandshake] false: a plain TCP connection, as «Проверить связь» does on both platforms (LinkCheck). */
    fun check(profile: StoredProfile, timeoutMs: Int = 30_000, tlsHandshake: Boolean = true): ProbeResult {
        val protocol = if (tlsHandshake) protocol(profile) else "TCP"
        val outbound = profile.selectedOutbound ?: return ProbeResult(message = "Нет выбранного сервера")
        val endpoint = OutboundPatch.read(outbound.singBoxJson)
        val port = endpoint.port.toIntOrNull()
        if (endpoint.server.isBlank() || port == null || port !in 1..65535) {
            return ProbeResult(message = "Не указан сервер или порт", serverProtocol = protocol)
        }
        return try {
            val started = TimeSource.Monotonic.markNow()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.server, port), timeoutMs)
                socket.soTimeout = timeoutMs
                if (protocol == "TLS") {
                    val context = SSLContext.getInstance("TLS")
                    // Probe only: detect a TLS response without changing sing-box certificate checks.
                    context.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
                    }), SecureRandom())
                    val sni = endpoint.sni.ifBlank { endpoint.server }
                    (context.socketFactory.createSocket(socket, endpoint.server, port, false) as SSLSocket).use { ssl ->
                        ssl.soTimeout = timeoutMs
                        if (sni.isNotBlank()) {
                            runCatching { SNIHostName(sni) }.getOrNull()?.let { name ->
                                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(name) }
                            }
                        }
                        ssl.startHandshake()
                    }
                }
            }
            ProbeResult(latencyMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1),
                message = if (protocol == "TLS") "TLS-рукопожатие" else "TCP до сервера",
                serverProtocol = protocol)
        } catch (error: Exception) {
            ProbeResult(message = app.lernet.engine.text.UserText.of(error, "Сервер не отвечает"), serverProtocol = protocol)
        }
    }

    /** UDP-based configs cannot pass a CONNECT on the local listener. */
    fun isTcp(profile: StoredProfile): Boolean {
        val type = profile.selectedOutbound?.singBoxJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonObject["type"]?.jsonPrimitive?.content }.getOrNull()
        }
        return type !in setOf("hysteria", "hysteria2", "tuic", "wireguard")
    }

    /**
     * Legacy CONNECT through the running tunnel. Not used for non-active «Проверить» any more
     * (that only proved the active path). Kept for any remaining call site that still needs it.
     */
    fun checkThroughTunnel(profile: StoredProfile, access: app.lernet.config.net.LocalProxyAccess, timeoutMs: Int = 15_000): ProbeResult {
        val protocol = protocol(profile)
        val outbound = profile.selectedOutbound ?: return ProbeResult(message = "Нет выбранного сервера")
        val endpoint = OutboundPatch.read(outbound.singBoxJson)
        val port = endpoint.port.toIntOrNull()
        if (endpoint.server.isBlank() || port == null || port !in 1..65535) {
            return ProbeResult(message = "Не указан сервер или порт", serverProtocol = protocol)
        }
        return try {
            val started = TimeSource.Monotonic.markNow()
            app.lernet.config.net.LocalProxyHttp.connectTunnel(access, endpoint.server, port, timeoutMs)
            ProbeResult(latencyMs = started.elapsedNow().inWholeMilliseconds.coerceAtLeast(1),
                message = "Сервер отвечает · проверено через VPN", serverProtocol = protocol)
        } catch (error: Exception) {
            ProbeResult(message = "Через VPN сервер не ответил", serverProtocol = protocol)
        }
    }
}
