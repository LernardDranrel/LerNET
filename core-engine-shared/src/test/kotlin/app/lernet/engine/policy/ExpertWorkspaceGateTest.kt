package app.lernet.engine.policy

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertWorkspaceGateTest {
    @Test fun `returns immediately when already ready`() = runBlocking {
        assertEquals("ok", ExpertWorkspaceGate.await(get = { "ok" }, refreshOnce = { error("no refresh") }))
    }

    @Test fun `forces one refresh then waits until ready`() = runBlocking {
        var value: String? = null
        var refreshes = 0
        val result = ExpertWorkspaceGate.await(
            get = { value },
            refreshOnce = {
                refreshes++
                value = "loaded"
            },
            timeoutMs = 2_000,
            stepMs = 50,
        )
        assertEquals("loaded", result)
        assertEquals(1, refreshes)
    }

    @Test fun `EditPolicy before ready does not throw — waits then succeeds`() = runBlocking {
        var runtime: MutableList<String>? = null
        val edits = mutableListOf<String>()
        // Mimic dispatch: await, then edit.
        val ready = ExpertWorkspaceGate.await(
            get = { runtime },
            refreshOnce = { runtime = mutableListOf() },
            timeoutMs = 2_000,
            stepMs = 50,
        )
        requireNotNull(ready).add("rule")
        edits += "rule"
        assertEquals(listOf("rule"), runtime)
        assertEquals(listOf("rule"), edits)
    }

    @Test fun `timeout returns null instead of hanging on ещё загружается`() = runBlocking {
        var refreshes = 0
        val result = ExpertWorkspaceGate.await(
            get = { null },
            refreshOnce = { refreshes++ },
            timeoutMs = 200,
            stepMs = 50,
        )
        assertNull(result)
        assertEquals(1, refreshes)
    }

    @Test fun `failed load stops waiting early`() = runBlocking {
        var failed = false
        val result = ExpertWorkspaceGate.await(
            get = { null },
            refreshOnce = { failed = true },
            failed = { failed },
            timeoutMs = 5_000,
            stepMs = 50,
        )
        assertNull(result)
        assertTrue(failed)
    }
}
