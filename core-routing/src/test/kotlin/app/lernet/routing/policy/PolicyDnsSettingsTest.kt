package app.lernet.routing.policy

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyDnsSettingsTest {
    @Test fun `old policies default to system DNS and new settings survive export`() {
        val old = Json.decodeFromString<NetworkPolicy>("{}")
        assertEquals(PolicyDnsMode.SYSTEM, old.dns.mode)
        val custom = old.copy(dns = PolicyDnsSettings(PolicyDnsMode.CUSTOM, "10.20.30.250"))
        assertEquals(custom, Json.decodeFromString<NetworkPolicy>(Json.encodeToString(custom)))
    }

    @Test fun `invalid custom server never passes validation or silently becomes system DNS`() {
        listOf(
            "", "example.com", "1.1.1.1:53", "1.1.1.999", "224.0.0.1",
            "01.1.1.1", "0.0.0.0", "255.255.255.255", "1.1.1.1\n", "１.1.1.1",
        ).forEach {
            val settings = PolicyDnsSettings(PolicyDnsMode.CUSTOM, it)
            assertFalse(it, settings.isValid())
            val issues = PolicyValidator.validate(NetworkPolicy(dns = settings), PolicyInventory(emptySet(), emptyMap()))
            assertTrue(issues.any { issue -> issue.field == "dns" })
        }
        assertTrue(PolicyDnsSettings().isValid())
        assertTrue(PolicyDnsSettings(PolicyDnsMode.CUSTOM, "10.20.30.250").isValid())
    }
}
