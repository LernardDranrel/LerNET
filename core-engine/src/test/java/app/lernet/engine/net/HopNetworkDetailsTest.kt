package app.lernet.engine.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HopNetworkDetailsTest {
    @Test
    fun combinesAnnouncingAsnHolderPrefixAndReverseName() {
        val requested = mutableListOf<String>()
        val lookup = RipeStatHopDetails { endpoint, resource ->
            requested += "$endpoint/$resource"
            when (endpoint) {
                "network-info" -> """{"data":{"asns":["15169"],"prefix":"8.8.8.0/24"}}"""
                "as-overview" -> """{"data":{"holder":"GOOGLE - Google LLC"}}"""
                "reverse-dns-ip" -> """{"data":{"result":"dns.google."}}"""
                "whois" -> """{"data":{"records":[[{"key":"OrgName","value":"Google LLC"}]]}}"""
                else -> null
            }
        }
        val expected = HopNetworkDetails(15169, "GOOGLE - Google LLC", "8.8.8.0/24", "dns.google", "Google LLC")
        assertThat(lookup.lookup("8.8.8.8")).isEqualTo(expected)
        assertThat(lookup.lookup("8.8.8.8")).isEqualTo(expected)
        assertThat(requested).containsExactly(
            "network-info/8.8.8.8",
            "as-overview/AS15169",
            "reverse-dns-ip/8.8.8.8",
            "whois/8.8.8.8",
        ).inOrder()
    }

    @Test
    fun privateHopsAndInvalidRepliesNeverInventMetadata() {
        val lookup = RipeStatHopDetails { _, _ -> error("Do not request private IPs") }
        assertThat(lookup.lookup("192.168.1.1")).isNull()
        val malformed = RipeStatHopDetails { _, _ -> "not json" }
        assertThat(malformed.lookup("8.8.8.8")).isNull()
    }
}
