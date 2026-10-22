package app.lernet.engine.policy

/**
 * What a «Проверить» button says while the handshake budget ([HandshakeBudget]) makes it wait:
 * the wait is shown at once instead of a silent spinner. Same words on Android and Windows;
 * the plain mode says «вы», Expert says «ты».
 */
object BudgetNotice {
    /** Whole seconds left, at least 1 (a wait that is about to end still shows «1 с»). */
    fun seconds(waitMs: Long): Int = ((waitMs.coerceAtLeast(1L) + 999L) / 1000L).toInt()

    /** Short form for one-line rows. */
    fun short(waitMs: Long): String = "Проверю через ${seconds(waitMs)} с, чтобы не привлекать внимание блокировщика"

    fun full(waitMs: Long, expert: Boolean): String =
        short(waitMs) + if (expert) " · можешь не ждать, результат появится здесь" else " · можете не ждать, результат появится здесь"
}
