package app.lernet.engine.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ExpertStatusDiagnosticsTest {
    private fun status(reason: String = "https_probe_certificate_failed", latency: Int = 0) =
        Json.parseToJsonElement("""{"running":true,"revision":1,"network_epoch":2,
            "exits":[{"tag":"private-profile","phase":"ready","health":"degraded",
            "reason":"$reason","latency_ms":$latency,"last_check_ms":$latency}],
            "folders":[{"tag":"private-folder","selected_tag":"private-profile"}],
            "flows":[{"destination":"private.example","password":"secret"}]}""") as JsonObject

    @Test fun `diagnostics include health cause but exclude profile and traffic data`() {
        val result = ExpertStatusDiagnostics().update(status(), true, 0)!!
        assertTrue(result.contains("https_probe_certificate_failed"))
        assertTrue(result.contains("folders_selected=1/1"))
        assertFalse(result.contains("private"))
        assertFalse(result.contains("secret"))
        assertTrue(ExpertStatusDiagnostics().update(status("secret-provider-text"), true, 0)!!.contains("unclassified"))
    }

    @Test fun `repeated polling and changing timestamps do not flood logs and pending state is retained`() {
        val logger = ExpertStatusDiagnostics()
        assertNotNull(logger.update(status(), true, 0))
        assertNull(logger.update(status(latency = 30), true, 12_000))
        assertNotNull(logger.update(status("https_probe_timeout"), true, 13_000))
        assertNull(logger.update(status("https_probe_dns_failed"), false, 14_000))
        val pending = logger.update(status("https_probe_dns_failed"), false, 23_000)!!
        assertTrue(pending.contains("https_probe_dns_failed"))
        assertTrue(pending.contains("underlay=false"))
    }
}
