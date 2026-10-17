package app.lernet.desktop

import app.lernet.engine.policy.RecheckJitter
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
    /** Quick tries (pauses up to 5, 10, 20 s) before the config counts as unavailable. */
    const val QUICK_ATTEMPTS = 3
    const val MAX_PAUSE_MS = RecheckJitter.MAX_MS

    /** True only for failures that a retry cannot fix; then the guard is released. */
    fun isTerminal(failure: TunnelFailure?): Boolean = failure == TunnelFailure.TUN_PERMISSION

    fun releasesGuard(failure: TunnelFailure?): Boolean = isTerminal(failure)

    fun retries(desired: Boolean, failure: TunnelFailure?, swapped: Boolean): Boolean =
        desired && !swapped && !isTerminal(failure)

    /**
     * First [QUICK_ATTEMPTS] retries: a short growing pause, random within its upper half.
     * After that the config is unavailable and is rechecked after a fresh random pause
     * of 45 s – 5 min in whole seconds ([RecheckJitter]); the network stays closed meanwhile.
     */
    fun pauseMs(attempt: Int, random: Random = Random.Default): Long {
        val safe = attempt.coerceAtLeast(1)
        if (safe > QUICK_ATTEMPTS) return RecheckJitter.nextMs(random)
        val upper = FIRST_PAUSE_MS shl (safe - 1)
        return random.nextLong(upper / 2, upper + 1)
    }

    fun message(guardEngaged: Boolean, pauseMs: Long, reason: String): String {
        val seconds = ((pauseMs + 999) / 1000).coerceAtLeast(1)
        val head = if (guardEngaged) "VPN включён · сеть закрыта, в обход ничего не уходит" else "VPN не работает"
        val tail = reason.trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        val wait = if (seconds >= 60) RecheckJitter.label(seconds * 1_000) else "$seconds с"
        return "$head · повтор через $wait$tail"
    }
}

/**
 * Configs of an auto-switch folder that failed are skipped by the next switch, but not forever:
 * once armed, they become candidates again after a fresh random pause of 45 s – 5 min.
 */
internal class UnavailableRecheck(private val draw: () -> Long = { RecheckJitter.nextMs() }) {
    @Volatile private var dueAt = 0L

    /** Arms the timer if it is not running; returns the pause until the recheck. */
    @Synchronized
    fun arm(now: Long): Long {
        if (dueAt == 0L) dueAt = now + draw()
        return dueAt - now
    }

    /** True once the pause is over; the timer is then disarmed. */
    @Synchronized
    fun consumeIfDue(now: Long): Boolean {
        if (dueAt == 0L || now < dueAt) return false
        dueAt = 0L
        return true
    }

    @Synchronized
    fun reset() { dueAt = 0L }
}
