package app.lernet.desktop

import app.lernet.engine.RunMode
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URL

internal data class TunnelHealthResult(val latencyMs: Long? = null, val error: String = "")

/** An HTTP request from the Windows process tests the path used by applications. */
internal object WindowsTunnelHealth {
    fun check(url: String, mode: RunMode, timeoutMs: Int = 5_000): TunnelHealthResult {
        val proxy = if (mode == RunMode.PROXY)
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 2080)) else Proxy.NO_PROXY
        val start = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            val request = (URL(url).openConnection(proxy) as HttpURLConnection).also { connection = it }
            request.connectTimeout = timeoutMs
            request.readTimeout = timeoutMs
            request.instanceFollowRedirects = false
            request.setRequestProperty("User-Agent", "LerNET-health/1")
            val code = request.responseCode
            if (code in 200..399) TunnelHealthResult((System.nanoTime() - start) / 1_000_000)
            else TunnelHealthResult(error = "HTTP $code")
        } catch (error: Exception) {
            TunnelHealthResult(error = error.message?.take(100) ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    fun networkSignature(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { iface ->
            runCatching { iface.isUp && !iface.isLoopback && !iface.isVirtual &&
                !iface.displayName.contains("LerNET", ignoreCase = true) }.getOrDefault(false)
        }
        .map { iface ->
            iface.name + ":" + iface.inetAddresses.toList()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { it.hostAddress }.sorted().joinToString(",")
        }
        .sorted().joinToString("|")
}
