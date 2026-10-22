package app.lernet.engine.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetNoticeTest {
    @Test
    fun secondsRoundUpAndNeverShowZero() {
        assertEquals(1, BudgetNotice.seconds(0L))
        assertEquals(1, BudgetNotice.seconds(1L))
        assertEquals(1, BudgetNotice.seconds(1_000L))
        assertEquals(2, BudgetNotice.seconds(1_001L))
        assertEquals(60, BudgetNotice.seconds(59_500L))
    }

    @Test
    fun plainSaysVyAndExpertSaysTy() {
        assertEquals("Проверю через 12 с, чтобы не привлекать внимание блокировщика", BudgetNotice.short(11_200L))
        assertTrue(BudgetNotice.full(11_200L, expert = false).contains("можете не ждать"))
        assertTrue(BudgetNotice.full(11_200L, expert = true).contains("можешь не ждать"))
    }
}
