package app.lernet.config.net

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.Proxy
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient

/**
 * Routes an OkHttp client through a LerNET local proxy that requires a login and password.
 *
 * Credentials are offered only to the proxy: for HTTPS OkHttp sends them in the CONNECT request
 * (preemptively), never inside the encrypted tunnel to the destination. A second 407 stops the
 * request instead of looping, and there is no fallback to a direct connection.
 */
object LocalProxyHttp {
    const val PROXY_AUTHORIZATION = "Proxy-Authorization"

    fun proxy(access: LocalProxyAccess): Proxy =
        Proxy(Proxy.Type.HTTP, InetSocketAddress(access.host, access.port))

    fun authenticator(access: LocalProxyAccess): Authenticator {
        val credential = Credentials.basic(access.username, access.password, Charsets.UTF_8)
        return Authenticator { _, response ->
            if (response.request.header(PROXY_AUTHORIZATION) != null) null
            else response.request.newBuilder().header(PROXY_AUTHORIZATION, credential).build()
        }
    }

    /** One authenticated CONNECT through the listener; succeeds only on a 2xx answer. */
    fun connectTunnel(access: LocalProxyAccess, host: String, port: Int, timeoutMs: Int) {
        require(port in 1..65535 && host.isNotBlank() && host.none { it == '\r' || it == '\n' || it == ' ' })
        val target = if (':' in host && !host.startsWith("[")) "[$host]:$port" else "$host:$port"
        Socket().use { socket ->
            socket.connect(InetSocketAddress(access.host, access.port), timeoutMs)
            socket.soTimeout = timeoutMs
            val credential = Credentials.basic(access.username, access.password, Charsets.UTF_8)
            val request = "CONNECT $target HTTP/1.1\r\nHost: $target\r\n$PROXY_AUTHORIZATION: $credential\r\n\r\n"
            socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
            val status = readLine(socket.getInputStream())
            val code = status.split(' ').getOrNull(1)?.toIntOrNull()
            if (code == null || code !in 200..299) throw IOException("Прокси ответил: ${status.take(64)}")
        }
    }

    private fun readLine(input: InputStream): String {
        val line = StringBuilder()
        while (line.length < 256) {
            val next = input.read()
            if (next == -1) break
            if (next == '\n'.code) break
            if (next != '\r'.code) line.append(next.toChar())
        }
        return line.toString()
    }

    fun via(builder: OkHttpClient.Builder, access: LocalProxyAccess): OkHttpClient.Builder =
        builder.proxy(proxy(access)).proxyAuthenticator(authenticator(access))
}
