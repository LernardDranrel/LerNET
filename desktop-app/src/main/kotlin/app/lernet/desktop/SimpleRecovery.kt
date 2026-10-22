package app.lernet.desktop

import app.lernet.engine.policy.RecheckJitter
import app.lernet.engine.policy.RetryBackoff
import app.lernet.engine.policy.SilentNetworkBackoff
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
    /**
     * Quick tries before the config counts as unavailable: the same four as Android with its default
     * five attempts (pauses [RetryBackoff]: 5, 15, 30, 30 s, each up to a quarter longer).
     */
    const val QUICK_ATTEMPTS = 4

    /** True only for failures that a retry cannot fix; then the guard is released. */
    fun isTerminal(failure: TunnelFailure?): Boolean = failure == TunnelFailure.TUN_PERMISSION

    fun releasesGuard(failure: TunnelFailure?): Boolean = isTerminal(failure)

    fun retries(desired: Boolean, failure: TunnelFailure?, swapped: Boolean): Boolean =
        desired && !swapped && !isTerminal(failure)

    /**
     * Pause before the core is started again, the same rule as Android:
     * - first [QUICK_ATTEMPTS] retries: [RetryBackoff] (5 → 15 → 30 s, capped at [RetryBackoff.DEFAULT_CAP_MS]);
     * - then the config is unavailable and is rechecked after a random 45 s – 5 min ([RecheckJitter]);
     * - in a series of rounds where nothing answered: [SilentNetworkBackoff], up to 15 min;
     * - never sooner than the server's handshake budget allows.
     */
    fun pauseMs(attempt: Int, silentRounds: Int = 0, budgetWaitMs: Long = 0L, random: Random = Random.Default): Long {
        val safe = attempt.coerceAtLeast(1)
        val pause = when {
            silentRounds >= ALL_SILENT_ROUNDS -> SilentNetworkBackoff.delayMs(silentRounds, random)
            safe > QUICK_ATTEMPTS -> RecheckJitter.nextMs(random)
            else -> RetryBackoff.delayMs(safe, RetryBackoff.DEFAULT_CAP_MS, 0L, random)
        }
        return maxOf(pause, budgetWaitMs)
    }

    /** Rounds without any answer before the status says white lists / shutdown (same as Android). */
    const val ALL_SILENT_ROUNDS = app.lernet.engine.policy.LinkJudge.ALL_SILENT_ROUNDS

    fun message(guardEngaged: Boolean, pauseMs: Long, reason: String): String {
        val seconds = ((pauseMs + 999) / 1000).coerceAtLeast(1)
        val head = if (guardEngaged) "VPN включён · сеть закрыта, в обход ничего не уходит" else "VPN не работает"
        val tail = reason.trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty()
        val wait = if (seconds >= 60) RecheckJitter.label(seconds * 1_000) else "$seconds с"
        return "$head · повтор через $wait$tail"
    }
}

/**
 * Runs the folder search off the diagnostics loop: probing a silent neighbour can take up to a
 * minute, and the loop must keep polling the core and checking health meanwhile.
 * One search at a time; a second request while one runs is dropped.
 */
internal class FailoverRunner(private val start: (Runnable) -> Unit = { Thread(it, "lernet-failover").apply { isDaemon = true }.start() }) {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    fun launch(block: () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        try {
            start(Runnable { try { block() } finally { running.set(false) } })
        } catch (error: Throwable) {
            running.set(false)
            throw error
        }
        return true
    }
}
