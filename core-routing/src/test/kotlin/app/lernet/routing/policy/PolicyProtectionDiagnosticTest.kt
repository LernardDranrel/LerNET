package app.lernet.routing.policy

import app.lernet.routing.RoutePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyProtectionDiagnosticTest {
    private val scope = PolicyScope.Profile("nl")
    private val inventory = PolicyInventory(setOf("nl"), emptyMap())
    private fun policy(child: PolicyTree, useTree: Boolean = true) = NetworkPolicy(
        device = PolicyTree(PolicyScope.Device, nodes = listOf(
            PolicyNode("entry", title = "Все остальные", protected = true,
                target = PolicyTarget.Profile("nl", routeScope = scope.takeIf { useTree })),
        )), trees = listOf(child),
    )

    @Test fun `protected profile identifies direct child instead of blaming VPN selection`() {
        val child = PolicyTree(scope, nodes = listOf(PolicyNode("ru", title = "Россия", target = PolicyTarget.Direct)),
            defaultTarget = PolicyTarget.CurrentExit)
        RoutePlatform.entries.forEach { platform ->
            val result = PolicyProgramCompiler.compile(policy(child), inventory, platform)
            assertFalse(result.isValid)
            val error = result.errors.single()
            assertEquals("ru", error.nodeId)
            assertTrue(error.message, error.message.contains("Дерево профиля → правило «Россия»"))
            assertTrue(error.message, error.message.contains("Напрямую"))
        }
    }

    @Test fun `direct default identifies child tree default path`() {
        val errors = PolicyValidator.validate(policy(PolicyTree(scope, defaultTarget = PolicyTarget.Direct)), inventory)
        assertTrue(errors.single().message, errors.single().message.contains("Дерево профиля → путь по умолчанию"))
    }

    @Test fun `explicitly skipping child keeps protected profile and block fallback`() {
        val child = PolicyTree(scope, nodes = listOf(PolicyNode("ru", target = PolicyTarget.Direct)),
            defaultTarget = PolicyTarget.CurrentExit)
        RoutePlatform.entries.forEach { platform ->
            val result = PolicyProgramCompiler.compile(policy(child, useTree = false), inventory, platform)
            assertTrue(result.errors.toString(), result.isValid)
            val protected = result.rules.first()
            assertTrue(protected.protected)
            assertEquals(PolicyTarget.Profile("nl", fallback = UnavailableFallback.BLOCK), protected.target.target)
            assertEquals(PolicyTarget.Direct, child.nodes.single().target)
        }
    }

    @Test fun `direct channel diagnostic names channel`() {
        val child = PolicyTree(scope, nodes = listOf(PolicyNode("ru", title = "Локальные", target = PolicyTarget.Channel("direct"))),
            defaultTarget = PolicyTarget.CurrentExit)
        val candidate = policy(child).copy(channels = listOf(PolicyChannel("direct", "Прямой канал", scope, PolicyTarget.Direct)))
        val error = PolicyValidator.validate(candidate, inventory).single()
        assertTrue(error.message, error.message.contains("канал «Прямой канал»"))
        assertEquals("ru", error.nodeId)
    }
}
