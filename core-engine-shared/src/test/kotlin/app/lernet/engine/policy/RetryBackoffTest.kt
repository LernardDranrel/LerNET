package app.lernet.engine.policy

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryBackoffTest {
    @Test fun stepsAreFiveFifteenFortyFiveNinetySecondsNotABurst() {
        val random = Random(5)
        repeat(200) {
            listOf(5_000L, 15_000L, 45_000L, 90_000L, 90_000L).forEachIndexed { index, base ->
                val delay = RetryBackoff.delayMs(index + 1, random = random)
                assertTrue("attempt ${index + 1}: $delay", delay in base..(base + base / 4))
            }
        }
    }

    @Test fun capIsHonouredButNeverBelowFiveSeconds() {
        assertEquals(30_000L, RetryBackoff.delayMs(4, capMs = 30_000L, random = Random(1)))
        assertEquals(5_000L, RetryBackoff.delayMs(3, capMs = 2_000L, random = Random(1)))
    }

    @Test fun handshakeBudgetWinsOverTheStep() {
        assertEquals(58_000L, RetryBackoff.delayMs(1, budgetWaitMs = 58_000L, random = Random(1)))
    }

    @Test fun silentNetworkChecksGetRareUpToFifteenMinutes() {
        val random = Random(9)
        val bases = listOf(1L, 2L, 5L, 10L, 15L, 15L).map { it * 60_000L }
        repeat(100) {
            bases.forEachIndexed { index, base ->
                val delay = SilentNetworkBackoff.delayMs(index + 1, random)
                assertTrue(delay in (base * 8 / 10)..(base * 12 / 10))
            }
        }
    }

    @Test fun restartLineHasReasonAttemptAndDelay() {
        assertEquals("restart reason=switch attempt=0 delay=0ms from=a to=b", RestartLog.line("switch", 0, 0, "from=a to=b"))
        assertEquals("restart reason=core-failed attempt=2 delay=15000ms", RestartLog.line("core-failed", 2, 15_000))
    }
}
