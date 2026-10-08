package app.lernet.engine.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExpertDirectNetworkFactsTest {
    private fun parse(raw: String) = directNetworkFacts(Json.parseToJsonElement(raw).jsonObject, 100)

    @Test fun `numeric epoch and finite capability states cross both platform contracts`() {
        val facts = parse(
            """{"network_epoch":3,"direct_families":{
                "ipv4":"available","ipv6":"unavailable","source":"windows_routes","interface":"Ethernet"}}""",
        )!!
        assertEquals(3, facts.networkEpoch)
        assertEquals(DirectFamilyAvailability.AVAILABLE, facts.ipv4)
        assertEquals(DirectFamilyAvailability.UNAVAILABLE, facts.ipv6)
        assertEquals("Ethernet", facts.interfaceName)
    }

    @Test fun `missing or malformed route evidence never means no route`() {
        assertNull(parse("""{"network_epoch":0}"""))
        assertNull(parse("""{"network_epoch":"0","direct_families":{}}"""))
        assertNull(parse("""{"network_epoch":-1,"direct_families":{}}"""))
        val unknown = parse(
            """{"network_epoch":0,"direct_families":{
                "ipv6":"new-state","source":"arbitrary-provider-text","interface":"bad\nname"}}""",
        )!!
        assertEquals(DirectFamilyAvailability.UNKNOWN, unknown.ipv6)
        assertEquals("unknown", unknown.source)
        assertNull(unknown.interfaceName)
    }
}
