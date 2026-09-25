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
)

/** Direct TCP/TLS handshake to the endpoint; the HTTP channel is checked separately. */
object EndpointProbe {
    private val json = Json { ignoreUnknownKeys = true }

    fun protocol(profile: StoredProfile): String {
        val tls = profile.selectedOutbound?.singBoxJson?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonObject["tls"] as? JsonObject }.getOrNull()
        }
        return if (tls?.get("enabled")?.jsonPrimitive?.booleanOrNull == true) "TLS" else "TCP"
    }

    fun check(profile: StoredProfile, timeoutMs: Int = 4_000): ProbeResult {
        val protocol = protocol(profile)
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
            ProbeResult(message = error.message?.take(100) ?: "Сервер не отвечает", serverProtocol = protocol)
        }
    }
}
