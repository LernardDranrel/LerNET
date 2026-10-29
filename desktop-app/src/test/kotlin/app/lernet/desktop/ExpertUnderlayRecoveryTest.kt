package app.lernet.desktop

import app.lernet.engine.policy.ExpertSessionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpertUnderlayRecoveryTest {
    @Test
    fun `retries failed start only for missing-adapter errors while still desired`() {
        assertTrue(
            ExpertUnderlayRecovery.shouldRetryFailedStart(
                desiredEnabled = true,
                phase = ExpertSessionPhase.FAILED,
                errors = listOf(
                    "Общий TUN не запущен: Нет подключённого физического адаптера. Подключите Wi‑Fi или Ethernet."
                ),
            )
        )
        assertFalse(
            ExpertUnderlayRecovery.shouldRetryFailedStart(
                desiredEnabled = false,
                phase = ExpertSessionPhase.FAILED,
                errors = listOf("Нет подключённого физического адаптера."),
            )
        )
        assertFalse(
            ExpertUnderlayRecovery.shouldRetryFailedStart(
                desiredEnabled = true,
                phase = ExpertSessionPhase.RUNNING,
                errors = listOf("Нет подключённого физического адаптера."),
            )
        )
        assertFalse(
            ExpertUnderlayRecovery.shouldRetryFailedStart(
                desiredEnabled = true,
                phase = ExpertSessionPhase.FAILED,
                errors = listOf("Ядро не подтвердило постоянный TUN."),
            )
        )
    }
}
