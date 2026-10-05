package app.lernet.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class ProbeDiagnosticsTest {
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
