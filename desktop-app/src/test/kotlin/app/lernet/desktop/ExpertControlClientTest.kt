package app.lernet.desktop

import app.lernet.engine.policy.PolicyControlCapabilities
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertControlClientTest {
    private val token = "control-token-" + "a".repeat(40)

    @Test
    fun `control refuses a non loopback address before sending credentials`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExpertControlClient(InetSocketAddress("192.0.2.1", 9000), token)
        }
    }

    @Test
    fun `capabilities require the actual native protocol and complete feature confirmation`() {
        withServer(
            """{"protocol_version":1,"preserves_tun":true,"hot_policy_apply":true,"atomic_prepare_commit":true,
            "independent_exit_lifecycle":true,"bounded_first_flow_wait":true,"destination_redirect":true}"""
        ) { server ->
            assertEquals(PolicyControlCapabilities(true, true, true), client(server).capabilities())
        }
        withServer("""{"protocol_version":2,"preserves_tun":true,"hot_policy_apply":true}""") { server ->
            assertEquals(PolicyControlCapabilities.RESTART_ONLY, client(server).capabilities())
        }
        withServer("""{"protocol_version":1,"preserves_tun":true,"hot_policy_apply":true}""") { server ->
            val capability = client(server).capabilities()
            assertTrue(capability.preservesTun)
            assertFalse(capability.atomicRules)
            assertFalse(capability.independentExits)
        }
    }

    @Test
    fun `a redirect never forwards the bearer to another listener`() {
        val forwarded = AtomicInteger()
        val destination = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        destination.createContext("/") { exchange ->
            forwarded.incrementAndGet()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        destination.start()
        try {
            withServer("{}", 302, "http://127.0.0.1:${destination.address.port}/") { server ->
                val error = assertThrows(IllegalStateException::class.java) { client(server).request("status") }
                assertTrue(error.message.orEmpty().contains("302"))
                assertEquals(0, forwarded.get())
            }
        } finally {
            destination.stop(0)
        }
    }

    @Test
    fun `native errors do not expose tokens or raw private JSON`() {
        val secret = "89ae3413-5381-4dcb-8198-3876a8531c41"
        withServer("""{"error":"rejected $token $secret","password":"private-value"}""", 409) { server ->
            val error = assertThrows(IllegalStateException::class.java) { client(server).request("status") }
            val message = error.message.orEmpty()
            assertFalse(message.contains(token))
            assertFalse(message.contains(secret))
            assertFalse(message.contains("private-value"))
            assertTrue(message.contains("409"))
        }
    }

    @Test
    fun `structured optional error preserves rejection status without exposing its private fields`() {
        listOf(
            """{"error":{"password":"private-native-secret"},"reason":["private-native-secret"]}""",
            """{"error":[{"password":"private-native-secret"}]}""",
            """{"error":true}""",
            """{"error":17}""",
        ).forEach { body ->
            withServer(body, 409) { server ->
                val failure = assertThrows(ExpertControlRejectedException::class.java) { client(server).request("status") }
                assertEquals(409, failure.status)
                assertTrue(failure.message.orEmpty().contains("409"))
                assertFalse(failure.message.orEmpty().contains("private-native-secret"))
                assertFalse(failure.message.orEmpty().contains("password"))
            }
        }
    }

    @Test
    fun `acknowledgement requires both interface identity and a valid revision`() {
        withServer("{}") { server ->
            val control = client(server)
            assertThrows(IllegalStateException::class.java) {
                control.acknowledgement(Json.parseToJsonElement("""{"instance_id":"native","revision":1}""").jsonObject)
            }
            assertThrows(IllegalStateException::class.java) {
                control.acknowledgement(
                    Json.parseToJsonElement("""{"instance_id":"native","interface_id":"tun","revision":-1}""").jsonObject
                )
            }
            listOf(
                """{"instance_id":true,"interface_id":"tun","revision":1}""",
                """{"instance_id":"native","interface_id":17,"revision":1}""",
                """{"instance_id":"native","interface_id":"tun","revision":"1"}""",
                """{"instance_id":{"password":"private-native-secret"},"interface_id":"tun","revision":1}""",
                """{"instance_id":"native","interface_id":[],"revision":1}""",
                """{"instance_id":"native","interface_id":"tun","revision":{"password":"private-native-secret"}}""",
            ).forEach { invalid ->
                assertThrows(IllegalStateException::class.java) {
                    control.acknowledgement(Json.parseToJsonElement(invalid).jsonObject)
                }
            }
            assertEquals(
                ExpertNativeAck("native", "tun", 42),
                control.acknowledgement(
                    Json.parseToJsonElement("""{"instance_id":"native","interface_id":"tun","revision":42}""").jsonObject,
                )
            )
        }
    }

    @Test
    fun `string flags cannot grant native capabilities`() {
        withServer(
            """{"protocol_version":1,"preserves_tun":"true","hot_policy_apply":true,
            "atomic_prepare_commit":true,"destination_redirect":true,"native_health_recovery":"true"}"""
        ) { server ->
            assertFalse(client(server).capabilities().preservesTun)
            assertFalse(client(server).capabilities().nativeHealthRecovery)
        }
    }

    @Test
    fun `a large native response is bounded`() {
        withServer("""{"data":"${"x".repeat(2048)}"}""") { server ->
            val control = ExpertControlClient(server.address, token, maxResponseBytes = 128)
            assertThrows(IllegalStateException::class.java) { control.request("status") }
        }
    }

    private fun client(server: HttpServer) = ExpertControlClient(server.address, token)

    private fun withServer(body: String, status: Int = 200, redirect: String? = null, test: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/lernet/v1/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token") {
                exchange.sendResponseHeaders(401, -1)
                exchange.close()
            } else {
                redirect?.let { exchange.responseHeaders.set("Location", it) }
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        try {
            test(server)
        } finally {
            server.stop(0)
        }
    }
}
