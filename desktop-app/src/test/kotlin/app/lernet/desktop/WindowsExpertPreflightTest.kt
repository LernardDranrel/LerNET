package app.lernet.desktop

import app.lernet.desktop.observation.ObservationCommandResult
import app.lernet.desktop.observation.ObservationCommandRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsExpertPreflightTest {
    @Test
    fun physicalRouteWithVpnLikeNameDoesNotClaimVpnOwnership() {
        val result = parse("""{"index":4,"name":"OtherVPN sing-tun","hardware":true,"virtual":false}""")
        assertTrue(result.complete)
        assertTrue(result.facts.any { it.contains("OtherVPN sing-tun") })
        assertFalse(result.warnings.any { it.contains("Windows выбирает виртуальный") })
        assertTrue(result.warnings.any { it.contains("могут отсутствовать") })
    }

    @Test
    fun virtualRouteAndConnectedPhonebookAreSeparateFacts() {
        val result = parse(
            """{"index":4,"name":"Tunnel","hardware":false,"virtual":true}""",
            """[{"name":"Office","status":"Connected","protocol":"Sstp","split":true},
                {"name":"Unused","status":"Disconnected","protocol":"Ikev2","split":false}]"""
        )
        assertTrue(result.warnings.any { it.contains("владелец по имени не определяется") })
        assertTrue(result.facts.any { it.contains("Office") && it.contains("Sstp") && it.contains("разделение трафика включено") })
        assertFalse(result.facts.any { it.contains("Unused") })
        assertFalse(result.warnings.any { it.contains("принадлежит") })
    }

    @Test
    fun missingAdapterAndUnavailableIpv6StayExplicitlyUnknown() {
        val result = WindowsExpertPreflight.parse(
            ObservationCommandResult(
                0,
                """{
            "adapters":[], "routes":[{"destination":"1.1.1.1","index":9,"name":"Unknown"},
            {"destination":"129.1.1.1","index":9,"name":"Unknown"},
            {"destination":"2606:4700:4700::1111","index":null,"name":""}], "vpns":[], "errors":["routes"] }"""
            )
        )
        assertFalse(result.complete)
        assertTrue(result.warnings.any { it.contains("тип неизвестен") })
        assertTrue(result.facts.any { it.contains("не нашла выбранный маршрут") })
    }

    @Test
    fun quotedHardwareBooleanIsNotAcceptedAsEvidence() {
        val result = parse("""{"index":4,"name":"Adapter","hardware":"true","virtual":false}""")
        assertFalse(result.complete)
        assertTrue(result.warnings.any { it.contains("не сообщила") })
    }

    @Test
    fun failureDoesNotExposeResponseOrProviderDiagnostics() = runBlocking {
        val secret = "private://credential"
        val preflight = WindowsExpertPreflight(
            ObservationCommandRunner { _, timeout ->
                assertEquals(8_000L, timeout)
                ObservationCommandResult(0, "invalid $secret", errorOutput = secret)
            },
            windows = true
        )
        val result = preflight.read()
        assertFalse(result.complete)
        assertFalse(result.toString().contains(secret))
    }

    @Test
    fun timedOutReadHasNoSuccessClaim() = runBlocking {
        val result = WindowsExpertPreflight(
            ObservationCommandRunner { _, _ ->
                ObservationCommandResult(null, "", timedOut = true)
            },
            windows = true
        ).read()
        assertFalse(result.complete)
        assertTrue(result.warnings.any { it.contains("8 секунд") })
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotConvertedIntoPermissionToProceed() = runBlocking {
        WindowsExpertPreflight(ObservationCommandRunner { _, _ -> throw CancellationException("cancel") }, windows = true).read()
        Unit
    }

    private fun parse(adapter: String, vpns: String = "[]"): ExpertPreflightResult =
        WindowsExpertPreflight.parse(
            ObservationCommandResult(
                0,
                """{
            "adapters":[$adapter], "routes":[{"destination":"1.1.1.1","index":4,"name":"Adapter"},
            {"destination":"129.1.1.1","index":4,"name":"Adapter"},
            {"destination":"2606:4700:4700::1111","index":4,"name":"Adapter"}], "vpns":$vpns, "errors":[] }"""
            )
        )
}
