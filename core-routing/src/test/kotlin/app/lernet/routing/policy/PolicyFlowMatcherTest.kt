package app.lernet.routing.policy

import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionJson
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RuleConditions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyFlowMatcherTest {
    @Test fun `first exact matched rule preserves node scope channel protection and redirect`() {
        val chosen = ProgramRule(
            condition("""{"domain":["example.invalid"]}"""),
            PolicyExit(PolicyTarget.Profile("profile", routeScope = null), "channel"),
            listOf("parent", "child"), listOf(PolicyScope.Device, PolicyScope.Profile("profile")), true,
            DestinationRedirect("192.0.2.10", 8443),
        )
        val result = PolicyFlowMatcher.preview(
            PolicyProgram(4, listOf(chosen, fallback())), PolicySimulationInput(domain = "EXAMPLE.INVALID"),
        )
        assertEquals(PolicyPreviewConfidence.MATCHED, result.confidence)
        assertEquals(listOf("parent", "child"), result.nodeIds)
        assertEquals(chosen.scopes, result.scopes)
        assertEquals(chosen.target, result.target)
        assertEquals(true, result.protected)
        assertEquals(chosen.redirect, result.redirect)
    }

    @Test fun `unknown protected branch prevents declaring direct fallback selected`() {
        val unknown = rule("""{"rule_set":["geoip-ru"]}""", PolicyTarget.Block)
        val result = PolicyFlowMatcher.preview(PolicyProgram(0, listOf(unknown, fallback())), PolicySimulationInput())
        assertEquals(PolicyPreviewConfidence.INCOMPLETE, result.confidence)
        assertNull(result.selected)
        assertEquals(2, result.candidates.size)
        assertTrue(result.missingFacts.any { it.contains("geoip-ru") })
    }

    @Test fun `false AND unknown condition is definitely false and does not obscure later default`() {
        val condition = ConditionJson.of(
            RuleConditions(
                MatchJoin.AND,
                listOf(
                    ConditionBlock(ConditionKind.DOMAIN, listOf("example.invalid")),
                    ConditionBlock(ConditionKind.PROCESS, listOf("browser.exe")),
                )
            )
        )
        val result = PolicyFlowMatcher.preview(
            PolicyProgram(0, listOf(rule(condition), fallback())),
            PolicySimulationInput(domain = "different.invalid"),
        )
        assertEquals(PolicyPreviewConfidence.MATCHED, result.confidence)
        assertEquals(PolicyTarget.Direct, result.target?.target)
        assertTrue(result.missingFacts.isEmpty())
    }

    @Test fun `logical inversions keep unknown metadata unknown`() {
        val input = PolicySimulationInput()
        assertEquals(PolicyMatchResult.UNKNOWN, match("""{"process_name":["browser.exe"],"invert":true}""", input))
        assertEquals(PolicyMatchResult.UNKNOWN, match("""{"type":"logical","mode":"or","rules":[{"domain":["x.invalid"]}]}""", input))
    }

    @Test fun `domain suffix matches whole labels and leading dot excludes parent`() {
        val suffix = """{"domain_suffix":["example.invalid"]}"""
        assertEquals(PolicyMatchResult.MATCH, match(suffix, PolicySimulationInput(domain = "www.example.invalid")))
        assertEquals(PolicyMatchResult.MATCH, match(suffix, PolicySimulationInput(domain = "example.invalid")))
        assertEquals(PolicyMatchResult.NO_MATCH, match(suffix, PolicySimulationInput(domain = "badexample.invalid")))
        assertEquals(
            PolicyMatchResult.NO_MATCH,
            match("""{"domain_suffix":[".example.invalid"]}""", PolicySimulationInput(domain = "example.invalid")),
        )
    }

    @Test fun `compiled separate domain and geo blocks retain AND instead of native default address OR`() {
        val compiled = ConditionJson.of(
            RuleConditions(
                MatchJoin.AND,
                listOf(
                    ConditionBlock(ConditionKind.DOMAIN, listOf("example.invalid")),
                    ConditionBlock(ConditionKind.GEOIP, listOf("ru")),
                )
            )
        )
        assertTrue(compiled["rules"] != null)
        assertEquals(
            PolicyMatchResult.NO_MATCH,
            PolicyFlowMatcher.match(compiled, PolicySimulationInput(domain = "elsewhere.invalid", geoCountry = "ru")).result,
        )
    }

    @Test fun `native default destination fields are OR while separately compiled blocks are AND`() {
        val input = PolicySimulationInput(domain = "elsewhere.invalid", destinationIp = "192.0.2.10")
        assertEquals(PolicyMatchResult.MATCH, match("""{"domain":["example.invalid"],"ip_cidr":["192.0.2.0/24"]}""", input))
        val separate = ConditionJson.of(
            RuleConditions(
                MatchJoin.AND,
                listOf(
                    ConditionBlock(ConditionKind.DOMAIN, listOf("example.invalid")),
                    ConditionBlock(ConditionKind.CIDR, listOf("192.0.2.0/24")),
                )
            )
        )
        assertEquals(PolicyMatchResult.NO_MATCH, PolicyFlowMatcher.match(separate, input).result)
    }

    @Test fun `opaque rule set mixed with address field never invents its native merge semantics`() {
        val input = PolicySimulationInput(domain = "different.invalid", ruleSetMatches = mapOf("custom" to true))
        assertEquals(PolicyMatchResult.UNKNOWN, match("""{"domain":["example.invalid"],"rule_set":["custom"]}""", input))
    }

    @Test fun `geo metadata and explicitly supplied rule set facts are simulated without loading databases`() {
        assertEquals(PolicyMatchResult.MATCH, match("""{"rule_set":["geoip-ru"]}""", PolicySimulationInput(geoCountry = "RU")))
        assertEquals(PolicyMatchResult.NO_MATCH, match("""{"rule_set":["geoip-ru"]}""", PolicySimulationInput(geoCountry = "DE")))
        assertEquals(
            PolicyMatchResult.MATCH,
            match("""{"rule_set":["custom"]}""", PolicySimulationInput(ruleSetMatches = mapOf("custom" to true))),
        )
        assertEquals(PolicyMatchResult.UNKNOWN, match("""{"rule_set":["custom"]}""", PolicySimulationInput(geoCountry = "RU")))
    }

    @Test fun `invalid country cannot falsely select direct fallback after a protected geo branch`() {
        val program = PolicyProgram(0, listOf(rule("""{"rule_set":["geoip-ru"]}"""), fallback()))
        listOf("Россия", "ZZ", "R", "RUS", " R U ", "RУ").forEach { country ->
            val result = PolicyFlowMatcher.preview(program, PolicySimulationInput(geoCountry = country))
            assertEquals(country, PolicyPreviewConfidence.INCOMPLETE, result.confidence)
            assertNull(result.selected)
            assertTrue(result.missingFacts.any { it.contains("двухбуквенный код ISO") })
        }
        val knownCountry = PolicyFlowMatcher.preview(program, PolicySimulationInput(geoCountry = "DE"))
        assertEquals(PolicyPreviewConfidence.MATCHED, knownCountry.confidence)
        assertEquals(PolicyTarget.Direct, knownCountry.target?.target)
    }

    @Test fun `opaque geo tagged set needs an explicit fact even when a valid country was supplied`() {
        assertEquals(
            PolicyMatchResult.UNKNOWN,
            match("""{"rule_set":["geoip-custom"]}""", PolicySimulationInput(geoCountry = "RU")),
        )
        assertEquals(
            PolicyMatchResult.MATCH,
            match("""{"rule_set":["geoip-custom"]}""", PolicySimulationInput(ruleSetMatches = mapOf("geoip-custom" to true))),
        )
        assertEquals(
            PolicyMatchResult.NO_MATCH,
            match(
                """{"rule_set":["geoip-ru"]}""",
                PolicySimulationInput(
                    geoCountry = "Россия", ruleSetMatches = mapOf("geoip-ru" to false),
                )
            ),
        )
    }

    @Test fun `CIDR comparison handles IPv4 IPv6 boundary prefixes and literal families`() {
        val v4 = """{"ip_cidr":["192.0.2.0/24"]}"""
        assertEquals(PolicyMatchResult.MATCH, match(v4, PolicySimulationInput(destinationIp = "192.0.2.255")))
        assertEquals(PolicyMatchResult.NO_MATCH, match(v4, PolicySimulationInput(destinationIp = "192.0.3.0")))
        assertEquals(
            PolicyMatchResult.MATCH,
            match("""{"ip_cidr":["2001:db8::/32"]}""", PolicySimulationInput(destinationIp = "2001:db8::1")),
        )
        assertEquals(PolicyMatchResult.NO_MATCH, match("""{"ip_cidr":["0.0.0.0/0"]}""", PolicySimulationInput(destinationIp = "::1")))
        assertEquals(
            PolicyMatchResult.MATCH,
            match("""{"ip_cidr":["::ffff:192.168.0.0/112"]}""", PolicySimulationInput(destinationIp = "::ffff:192.168.1.2")),
        )
    }

    @Test fun `invalid IP and CIDR strings stay unknown and never trigger hostname resolution`() {
        listOf("example.invalid", "127.1", "001.2.3.4", "256.1.1.1", "fe80::1%eth0", "2001:::1").forEach { ip ->
            assertEquals(PolicyMatchResult.UNKNOWN, match("""{"ip_cidr":["0.0.0.0/0"]}""", PolicySimulationInput(destinationIp = ip)))
        }
        assertEquals(
            PolicyMatchResult.UNKNOWN,
            match("""{"ip_cidr":["192.0.2.0/99"]}""", PolicySimulationInput(destinationIp = "192.0.2.1")),
        )
    }

    @Test fun `private predicate mirrors pinned nonpublic ranges including multicast unspecified and mapped IPv4`() {
        listOf(
            "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "127.0.0.1", "224.0.0.1", "0.0.0.0",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "::ffff:10.1.2.3"
        ).forEach { ip ->
            assertEquals(ip, PolicyMatchResult.MATCH, match("""{"ip_is_private":true}""", PolicySimulationInput(destinationIp = ip)))
        }
        assertEquals(PolicyMatchResult.NO_MATCH, match("""{"ip_is_private":true}""", PolicySimulationInput(destinationIp = "8.8.8.8")))
    }

    @Test fun `process package and protocol facts must be supplied independently`() {
        val input = PolicySimulationInput(processName = "browser.exe", packageName = "org.example", network = "tcp", protocol = "tls")
        assertEquals(PolicyMatchResult.MATCH, match("""{"process_name":["browser.exe"],"network":"tcp","protocol":"tls"}""", input))
        assertEquals(PolicyMatchResult.MATCH, match("""{"package_name":["org.example"]}""", input))
        assertEquals(PolicyMatchResult.UNKNOWN, match("""{"protocol":"tls"}""", PolicySimulationInput(network = "tcp")))
    }

    @Test fun `network and protocol comparisons retain exact native metadata semantics`() {
        assertEquals(
            PolicyMatchResult.MATCH,
            match(
                """{"network":"tcp","protocol":"tls"}""",
                PolicySimulationInput(network = "tcp", protocol = "tls")
            )
        )
        assertEquals(PolicyMatchResult.NO_MATCH, match("""{"network":"TCP"}""", PolicySimulationInput(network = "tcp")))
        assertEquals(PolicyMatchResult.NO_MATCH, match("""{"protocol":"tls"}""", PolicySimulationInput(protocol = "TLS")))
    }

    @Test fun `ports ranges and source address groups combine as native default rule groups`() {
        val input = PolicySimulationInput(destinationPort = 443, sourcePort = 1234, sourceIp = "10.0.0.1")
        val groups = """{"port":[80],"port_range":["400:500"],"source_port_range":[":2000"],"source_ip_cidr":["10.0.0.0/8"]}"""
        assertEquals(PolicyMatchResult.MATCH, match(groups, input))
        assertEquals(PolicyMatchResult.UNKNOWN, match("""{"port_range":["bad:443"]}""", input))
        assertEquals(PolicyMatchResult.MATCH, match("""{"port":[]}""", PolicySimulationInput()))
    }

    @Test fun `arbitrary regex and unsupported fields remain unknown rather than executing or guessing`() {
        assertEquals(
            PolicyMatchResult.UNKNOWN,
            match("""{"domain_regex":["(a+)+$"]}""", PolicySimulationInput(domain = "a".repeat(10_000))),
        )
        assertEquals(PolicyMatchResult.UNKNOWN, match("""{"wifi_ssid":["private-network"]}""", PolicySimulationInput()))
    }

    private fun condition(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
    private fun match(raw: String, input: PolicySimulationInput): PolicyMatchResult = PolicyFlowMatcher.match(condition(raw), input).result
    private fun rule(raw: String, target: PolicyTarget = PolicyTarget.Block): ProgramRule = rule(condition(raw), target)
    private fun rule(body: JsonObject, target: PolicyTarget = PolicyTarget.Block): ProgramRule =
        ProgramRule(body, PolicyExit(target), listOf("rule"), listOf(PolicyScope.Device), target == PolicyTarget.Block)
    private fun fallback(): ProgramRule = rule(JsonObject(emptyMap()), PolicyTarget.Direct)
}
