package app.lernet.config.net

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class AppTrafficTest {
    private val base = OkHttpClient.Builder().build()

    @After fun reset() = AppTraffic.set(AppTraffic.Route.Direct)

    @Test
    fun connectionOffKeepsNormalDirectRequests() {
        AppTraffic.set(AppTraffic.Route.Direct)
        assertThat(AppTraffic.client(base)).isSameInstanceAs(base)
        assertThat(AppTraffic.routed).isFalse()
    }

    @Test
    fun recoveringConnectionRefusesInsteadOfGoingDirect() {
        AppTraffic.set(AppTraffic.Route.Closed)
        assertThrows(AppTraffic.ClosedException::class.java) { AppTraffic.client(base) }
        var directCalled = false
        assertThrows(AppTraffic.ClosedException::class.java) {
            AppTraffic.tcpConnect("example.com", 443, 1_000) { _, _, _ -> directCalled = true }
        }
        assertThat(directCalled).isFalse()
        assertThat(AppTraffic.routed).isTrue()
    }

    @Test
    fun runningConnectionSendsRequestsOnlyThroughTheInternalListener() {
        MockWebServer().use { listener ->
            listener.enqueue(MockResponse().setResponseCode(407).addHeader("Proxy-Authenticate", "Basic"))
            listener.enqueue(MockResponse().setBody("ok"))
            listener.start()
            AppTraffic.set(AppTraffic.Route.Through(LocalProxyAccess(listener.port, "app", "secret")))
            val client = AppTraffic.client(base)
            assertThat(client.proxy).isEqualTo(Proxy(Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", listener.port)))
            client.newCall(Request.Builder().url("http://subscription.invalid/list").build()).execute().use {
                assertThat(it.body!!.string()).isEqualTo("ok")
            }
            listener.takeRequest(5, TimeUnit.SECONDS)
            val authed = listener.takeRequest(5, TimeUnit.SECONDS)!!
            assertThat(authed.getHeader("Proxy-Authorization")).isEqualTo(okhttp3.Credentials.basic("app", "secret"))
            assertThat(authed.requestLine).startsWith("GET http://subscription.invalid/list")
        }
    }

    @Test
    fun tcpCheckThroughListenerUsesAuthenticatedConnectAndNoLocalDns() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var request = ""
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    request = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                    socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                }
            }
            AppTraffic.set(AppTraffic.Route.Through(LocalProxyAccess(server.localPort, "app", "secret")))
            AppTraffic.tcpConnect("host.invalid", 8443, 3_000) { _, _, _ -> error("must not dial directly") }
            worker.join(3_000)
            assertThat(request).startsWith("CONNECT host.invalid:8443 HTTP/1.1")
            assertThat(request).contains("Proxy-Authorization: " + okhttp3.Credentials.basic("app", "secret"))
        }
    }

    @Test
    fun tcpCheckFailsWhenListenerRejectsPassword() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.count()
                    socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray())
                }
            }
            AppTraffic.set(AppTraffic.Route.Through(LocalProxyAccess(server.localPort, "app", "wrong")))
            assertThrows(java.io.IOException::class.java) {
                AppTraffic.tcpConnect("host.invalid", 443, 3_000) { _, _, _ -> error("must not dial directly") }
            }
            worker.join(3_000)
        }
    }
}
