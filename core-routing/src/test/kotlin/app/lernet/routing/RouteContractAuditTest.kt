package app.lernet.routing

import org.junit.Assert.*
import org.junit.Test

class RouteContractAuditTest {
    private val fallback = RuleNode("else", null, true, 100, RuleMatch(), RouteAction.PROXY)
    private fun rule(id: String = "rule") = RuleNode(id, null, true, 0,
        RuleMatch(domains = listOf("example.org")), RouteAction.DIRECT)

    @Test fun structuredConditionsAreAuthoritativeEvenWhenLegacyFieldsAreEmptyOrStale() {
        val conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.DOMAIN, listOf("work.example"))))
        listOf(RuleMatch(), RuleMatch(ipCidrs = listOf("invalid"))).forEach { legacy ->
            val compiled = RouteCompiler.compile(listOf(rule().copy(match = legacy, conditions = conditions), fallback))
            assertTrue(compiled.errors.toString(), compiled.isValid)
            assertEquals(listOf("work.example"), compiled.rules.single().match.domains)
        }
    }

    @Test fun invalidStructuredConditionsCannotHideBehindValidLegacyFields() {
        val conditions = RuleConditions(blocks = listOf(ConditionBlock(ConditionKind.CIDR, listOf("invalid"))))
        val compiled = RouteCompiler.compile(listOf(rule().copy(conditions = conditions), fallback))
        assertFalse(compiled.isValid)
        assertTrue(compiled.errors.any { it.field == "ip_cidr" })
    }

    @Test fun brokenOrIncompleteBlocksCannotSilentlyChangeTheCondition() {
        val broken = ConditionCodec.decode("{broken", rule().match)
        assertFalse(RouteCompiler.compile(listOf(rule().copy(conditions = broken), fallback)).isValid)
        val incomplete = RuleConditions(MatchJoin.AND, listOf(
            ConditionBlock(ConditionKind.DOMAIN, listOf("example.org")), ConditionBlock(ConditionKind.CIDR)))
        assertFalse(RouteCompiler.compile(listOf(rule().copy(conditions = incomplete), fallback)).isValid)
    }

    @Test fun unfinishedOrphanBranchCannotBlockTheActiveTree() {
        val loose = rule("loose").copy(parentId = RouteTree.ORPHAN, pipeName = "unfinished")
        val child = rule("child").copy(parentId = loose.id, match = RuleMatch(ipCidrs = listOf("invalid")))
        val compiled = RouteCompiler.compile(listOf(rule(), fallback, loose, child))
        assertTrue(compiled.errors.toString(), compiled.isValid)
        assertEquals(listOf("rule"), compiled.rules.map { it.nodeId })
    }

    @Test fun disabledBranchCannotValidateOrEmitItsChildren() {
        val parent = rule().copy(enabled = false)
        val child = rule("child").copy(parentId = parent.id, match = RuleMatch(ipCidrs = listOf("invalid")))
        val compiled = RouteCompiler.compile(listOf(parent, child, fallback))
        assertTrue(compiled.errors.toString(), compiled.isValid)
        assertTrue(compiled.rules.isEmpty())
        assertEquals(RouteAction.PROXY, compiled.finalAction)
        assertFalse(RouteCompiler.compile(listOf(fallback.copy(enabled = false))).isValid)
        val foreign = rule().copy(match = RuleMatch(apps = listOf("com.example.app")))
        assertFalse(RouteCompiler.compile(listOf(foreign, fallback.copy(enabled = false)), RoutePlatform.WINDOWS).isValid)
    }

    @Test fun WindowsExecutableNamesMayContainSpacesAndUnicodeButNotPaths() {
        listOf("My Browser.exe", "Браузер.exe", "!My Browser.exe").forEach { name ->
            val compiled = RouteCompiler.compile(listOf(rule().copy(match = RuleMatch(processes = listOf(name))), fallback), RoutePlatform.WINDOWS)
            assertTrue("$name: ${compiled.errors}", compiled.isValid)
        }
        listOf("C:\\Browser.exe", "../Browser.exe", "bad|name.exe").forEach { name ->
            assertFalse(RouteCompiler.compile(listOf(rule().copy(match = RuleMatch(processes = listOf(name))), fallback)).isValid)
        }
    }

    @Test fun malformedIPv6CannotPassValidationAsANumericAddress() {
        listOf(":::/64", "abcd/64", "12345::/64", "1:2:3:4:5:6:7:8:9/64").forEach { cidr ->
            assertFalse(cidr, RouteCompiler.compile(listOf(rule().copy(match = RuleMatch(ipCidrs = listOf(cidr))), fallback)).isValid)
        }
        listOf("::/0", "2001:db8::/32", "::1/128").forEach { cidr ->
            assertTrue(cidr, RouteCompiler.compile(listOf(rule().copy(match = RuleMatch(ipCidrs = listOf(cidr))), fallback)).isValid)
        }
    }
}
