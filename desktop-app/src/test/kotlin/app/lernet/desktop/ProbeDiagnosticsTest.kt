package app.lernet.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.jsonObject

class ProbeDiagnosticsTest {
    @Test fun failoverProbeEscapesActiveTunWithoutMovingItsLocalListener() {
        val raw = """{"inbounds":[{"listen":"127.0.0.1","listen_port":4321}],"outbounds":[{"tag":"proxy","type":"vless"}],"route":{"final":"proxy","auto_detect_interface":true}}"""
        val before = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val after = kotlinx.serialization.json.Json.parseToJsonElement(OutboundProbe.bindProbeUnderlay(raw, "Ethernet")).jsonObject
        assertEquals(before["inbounds"], after["inbounds"])
        assertEquals(before["outbounds"], after["outbounds"])
        val route = after.getValue("route").jsonObject
        assertEquals(before.getValue("route").jsonObject["final"], route["final"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("Ethernet"), route["default_interface"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(false), route["auto_detect_interface"])
    }

    @Test fun privateValuesAndAnsiAreRemovedButSocketFailureIsKept() {
        val line = ProbeDiagnostics.clean("\u001B[36mERROR password=fixture-secret uuid=fixture-uuid https://user:pass@example.org/check?token=fixture-token#private connection refused")
        assertFalse(line.contains("fixture"))
        assertFalse(line.contains("user:pass"))
        assertFalse(line.contains("\u001B"))
        assertTrue(line.contains("connection refused"))
        assertFalse(ProbeDiagnostics.clean("Authorization: Bearer fixture-token vless://fixture-uuid@host:443").contains("fixture"))
        assertTrue(ProbeDiagnostics.cause(IllegalStateException("API 503", java.net.ConnectException("Connection refused"))).contains("ConnectException"))
    }

    @Test fun exhaustedBudgetDoesNotAttemptAnotherRequest() {
        assertTrue(ProbeDiagnostics.httpDetail("http://unresolvable.invalid/", 1, 0).contains("исчерпан"))
    }

    @Test fun diagnosticHttpUsesSpecifiedProxyInsteadOfResolvingTargetDirectly() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var target = ""
        server.createContext("/") { exchange ->
            target = exchange.requestURI.toString()
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = ProbeDiagnostics.httpDetail("http://unresolvable.invalid/check", server.address.port, 3000)
            assertTrue(result, result.contains("204"))
            assertEquals("http://unresolvable.invalid/check", target)
        } finally { server.stop(0) }
    }

    @Test fun foreignTunnelWarnsWhileOwnTunnelAndEthernetDoNot() {
        assertTrue(
            EndpointRouteObservation.describe("198.51.100.33", 50, "blacktemple", "tun2socks Tunnel").warning.contains("blacktemple")
        )
        assertEquals("", EndpointRouteObservation.describe("198.51.100.33", 50, "LerNET", "sing-tun Tunnel").warning)
        assertEquals("", EndpointRouteObservation.describe("198.51.100.33", 8, "Ethernet", "Realtek Gaming Controller").warning)
    }
}
