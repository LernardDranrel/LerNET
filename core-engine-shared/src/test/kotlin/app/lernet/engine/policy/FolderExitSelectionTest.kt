package app.lernet.engine.policy

import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderExitSelectionTest {
    private val members = listOf("de", "nl")
    private val policy = FolderPolicy("folder", autoSwap = true, freshnessMs = 1_000)

    @Test
    fun `recovery cannot reselect failed current exit from its previous successful sample`() {
        val samples = listOf(ExitHealthSample("de", 0, 10), ExitHealthSample("nl", 0, 20))
        val decision = FolderExitSelection.select(policy, members, samples, 1, "de", recovering = true)
        assertEquals(FolderExitDecision.Selected("nl"), decision)
        val onlyCurrent = FolderExitSelection.select(policy, listOf("de"), samples, 1, "de", recovering = true)
        assertEquals(FolderExitDecision.CheckRequired(listOf("de")), onlyCurrent)
    }

    @Test fun `missing or stale HTTPS result requires check instead of selecting reachable endpoint`() {
        assertEquals(FolderExitDecision.CheckRequired(members), FolderExitSelection.select(policy, members, emptyList(), 0))
        val stale = listOf(ExitHealthSample("de", 0, 10), ExitHealthSample("nl", 0, null))
        assertEquals(FolderExitDecision.CheckRequired(members), FolderExitSelection.select(policy, members, stale, 1_001))
    }

    @Test fun `preferred failover fastest ties and stable current use folder policy`() {
        val samples = listOf(ExitHealthSample("de", 0, 100), ExitHealthSample("nl", 0, 20))
        assertEquals(FolderExitDecision.Selected("de"), FolderExitSelection.select(policy, members, samples, 1))
        val fastest = policy.copy(selection = FolderSelection.LOWEST_LATENCY)
        assertEquals(FolderExitDecision.Selected("nl"), FolderExitSelection.select(fastest, members, samples, 1))
        assertEquals(FolderExitDecision.Selected("de"), FolderExitSelection.select(fastest, members, samples, 1, "de"))
        val failed = samples.map { if (it.profileId == "de") it.copy(httpsLatencyMs = null, failedUntilMs = 100) else it }
        assertEquals(FolderExitDecision.Selected("nl"), FolderExitSelection.select(policy, members, failed, 1, "de", true))
    }

    @Test fun `disabled auto swap retries current without using neighbor`() {
        val samples = listOf(ExitHealthSample("de", 0, null), ExitHealthSample("nl", 0, 20))
        assertEquals(
            FolderExitDecision.CheckRequired(listOf("de")),
            FolderExitSelection.select(policy.copy(autoSwap = false), members, samples, 1, "de", true)
        )
    }

    @Test fun `newest failure supersedes earlier success and cooldown prevents retry storm`() {
        val samples = listOf(ExitHealthSample("de", 0, 10), ExitHealthSample("de", 1, null, 100), ExitHealthSample("nl", 1, null, 100))
        assertEquals(FolderExitDecision.Unavailable, FolderExitSelection.select(policy, members, samples, 2))
    }
}
