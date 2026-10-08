package app.lernet.engine.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertProbeFailureTest {
    @Test fun `HTTPS failures explain the failed check without claiming TUN is stopped`() {
        val codes = listOf("https_probe_failed", "https_probe_certificate_failed", "https_probe_dns_failed", "https_probe_timeout")
        for (code in codes) {
            val explanation = expertNativeFailureExplanation(code)
            assertTrue(explanation.contains("HTTPS"))
            assertFalse(explanation.contains(code))
        }
        assertTrue(expertNativeFailureExplanation("https_probe_certificate_failed").contains("не означает, что TUN выключен"))
        assertTrue(expertNativeFailureExplanation("folder_has_no_healthy_exit").contains("папки"))
    }
}
