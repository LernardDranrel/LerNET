package app.lernet.desktop

import app.lernet.engine.compile.ConfigAssembler
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Item 9 of the audit: the same defaults and the same DNS meaning as on Android. */
class DesktopParityDefaultsTest {
    private fun profile(id: String, dns: String = "SYSTEM") =
        StoredProfile(id, id, "VLESS", listOf(StoredOutbound("o", "proxy", "vless", "{}")), "o", dnsPolicy = dns)

    @Test
    fun followTheGlobalSettingBecomesTheGlobalValueOnce() {
        val state = StoredState(defaultDnsPolicy = "UNDERLAY", profiles = listOf(profile("a"), profile("b", "PROFILE")))
        val explicit = state.withExplicitDns()
        assertEquals(listOf("UNDERLAY", "PROFILE"), explicit.profiles.map { it.dnsPolicy })
        // Changing the global setting afterwards no longer moves an existing config's DNS.
        assertEquals("UNDERLAY", explicit.copy(defaultDnsPolicy = "TUNNEL").withExplicitDns().profiles.first().dnsPolicy)
        val clean = StoredState(profiles = listOf(profile("c", "TUNNEL")))
        assertSame(clean, clean.withExplicitDns())
    }

    @Test
    fun oldStateFileWithoutDnsChoiceLoadsAsTheGlobalValue() {
        val dir = Files.createTempDirectory("lernet-dns-legacy")
        val store = DesktopStore(dir)
        store.save(StoredState(defaultDnsPolicy = "UNDERLAY", profiles = listOf(profile("a"))))
        assertEquals("UNDERLAY", store.load().withExplicitDns().profiles.single().dnsPolicy)
    }

    @Test
    fun journalLevelAndCheckAddressMatchAndroid() {
        val defaults = StoredState()
        assertEquals("warn", defaults.logLevel)
        assertEquals(ConfigAssembler.ANDROID_PROBE_URL, defaults.healthUrl)
    }
}
