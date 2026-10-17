package app.lernet.desktop

import kotlin.random.Random

/**
 * What the simple VPN on Windows does after its tunnel failed while the user still wants it on.
 *
 * Only a terminal failure (Windows refused to create the TUN adapter) gives the network back.
 * Any other failure (preflight error, core check failed, the failover candidate could not start)
 * keeps the crash guard engaged — everything outside the LerNET adapter stays closed — and the
 * connection is retried after a pause until it works or the user presses Stop.
 */
internal object SimpleRecovery {
    const val FIRST_PAUSE_MS = 5_000L
    const val MAX_PAUSE_MS = 60_000L

    /** True only for failures that a retry cannot fix; then the guard is released. */
    fun isTerminal(failure: TunnelFailure?): Boolean = failure == TunnelFailure.TUN_PERMISSION

    fun releasesGuard(failure: TunnelFailure?): Boolean = isTerminal(failure)

    fun retries(desired: Boolean, failure: TunnelFailure?, swapped: Boolean): Boolean =
        desired && !swapped && !isTerminal(failure)

    /** Growing pause, capped, randomised within its upper half so retries never form a fixed rhythm. */
    fun pauseMs(attempt: Int, random: Random = Random.Default): Long {
        val step = (attempt.coerceAtLeast(1) - 1).coerceAtMost(8)
        val upper = (FIRST_PAUSE_MS shl step).coerceAtMost(MAX_PAUSE_MS)
        return random.nextLong(upper / 2, upper + 1)
    }

    fun message(guardEngaged: Boolean, pauseMs: Long, reason: String): String {
        val seconds = ((pauseMs + 999) / 1000).coerceAtLeast(1)
        val head = if (guardEngaged) "VPN включён · сеть закрыта, в обход ничего не уходит" else "VPN не работает"
        val tail = reason.trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        return "$head · повтор через $seconds с$tail"
    }
}
