package app.lernet.routing

import org.junit.Assert.*
import org.junit.Test

class RoutePlatformTest {
    private fun node(id: String, match: RuleMatch = RuleMatch(), parent: String? = null, order: Int = 0) =
        RuleNode(id, parent, true, order, match, RouteAction.DIRECT)

    @Test fun eachPlatformUsesItsOwnBranchesAndKeepsFallback() {
        val nodes = listOf(
            node("android", RuleMatch(apps = listOf("org.example.app"))),
            node("windows", RuleMatch(processes = listOf("browser.exe")), order = 1),
            node("fallback", order = 2).copy(action = RouteAction.PROXY),
        )
        val android = RouteCompiler.compile(nodes, RoutePlatform.ANDROID)
        val windows = RouteCompiler.compile(nodes, RoutePlatform.WINDOWS)
        assertTrue(android.isValid)
        assertTrue(windows.isValid)
        assertEquals(listOf("android"), android.rules.map { it.nodeId })
        assertEquals(listOf("windows"), windows.rules.map { it.nodeId })
        assertEquals(setOf("windows"), android.inactiveNodeIds)
        assertEquals(setOf("android"), windows.inactiveNodeIds)
        assertEquals(RouteAction.PROXY, windows.finalAction)
        assertTrue(nodes.all { it.enabled })
    }

    @Test fun descendantsAndOtherwiseCannotEscapeUnsupportedParent() {
        for (platform in RoutePlatform.entries) {
            val match = if (platform == RoutePlatform.WINDOWS) RuleMatch(apps = listOf("org.example.app"))
                else RuleMatch(processes = listOf("browser.exe"))
            val nodes = listOf(node("foreign", match),
                node("domain", RuleMatch(domains = listOf("example.com")), "foreign"),
                node("nested", RuleMatch(domains = listOf("nested.example.com")), "domain"),
                node("else", parent = "foreign", order = 1),
                node("fallback", order = 1).copy(action = RouteAction.BLOCK))
            val route = RouteCompiler.compile(nodes, platform)
            assertTrue(route.isValid)
            assertTrue(route.rules.isEmpty())
            assertEquals(RouteAction.BLOCK, route.finalAction)
            assertEquals(setOf("foreign", "domain", "nested", "else"), route.inactiveNodeIds)
        }
    }

    @Test fun mixedOrConditionDeactivatesEntireBranchInsteadOfBroadeningMatch() {
        val node = node("mixed").copy(conditions = RuleConditions(MatchJoin.OR, listOf(
            ConditionBlock(ConditionKind.APP, listOf("invalid package name")),
            ConditionBlock(ConditionKind.DOMAIN, listOf("example.com")),
        )))
        val route = RouteCompiler.compile(listOf(node), RoutePlatform.WINDOWS)
        assertTrue(route.isValid)
        assertTrue(route.rules.isEmpty())
        assertEquals(RouteAction.PROXY, route.finalAction)
        assertEquals(setOf("mixed"), route.inactiveNodeIds)
    }

    @Test fun removingForeignConditionReactivatesBranch() {
        val original = node("app", RuleMatch(apps = listOf("org.example.app")))
        val edited = original.copy(match = RuleMatch(processes = listOf("browser.exe")))
        val fallback = node("fallback", order = 1).copy(action = RouteAction.PROXY)
        assertEquals(setOf("app"), RouteCompiler.compile(listOf(original), RoutePlatform.WINDOWS).inactiveNodeIds)
        val route = RouteCompiler.compile(listOf(edited, fallback), RoutePlatform.WINDOWS)
        assertTrue(route.isValid)
        assertTrue(route.inactiveNodeIds.isEmpty())
        assertEquals(listOf("browser.exe"), route.rules.single().match.processes)
    }
}
