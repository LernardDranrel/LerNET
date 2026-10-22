package app.lernet.engine.policy

import kotlin.random.Random

/**
 * Pause before the same config is started again after its core really failed (not after silence).
 * 5 → 15 → 45 → 90 s, each up to a quarter longer at random, capped by the user's ceiling but never
 * below 5 s, and never sooner than the handshake budget allows ([HandshakeBudget]).
 * Replaces the old 1/2/4/8 s burst: five restarts in 15 s are five bursts of handshakes.
 */
object RetryBackoff {
    val STEPS_MS = listOf(5_000L, 15_000L, 45_000L, 90_000L)
    const val FLOOR_MS = 5_000L

    /** Android's default ceiling (Settings → reconnect, 2–120 s); Windows has no setting and uses it as is. */
    const val DEFAULT_CAP_MS = 30_000L

    fun delayMs(attempt: Int, capMs: Long = Long.MAX_VALUE, budgetWaitMs: Long = 0L, random: Random = Random.Default): Long {
        val base = STEPS_MS[(attempt.coerceAtLeast(1) - 1).coerceAtMost(STEPS_MS.size - 1)]
        val jittered = base + random.nextLong(0L, base / 4 + 1)
        val capped = jittered.coerceAtMost(capMs.coerceAtLeast(FLOOR_MS))
        return maxOf(capped, budgetWaitMs)
    }
}

/**
 * Every server is silent while the device itself has a network: the operator may let only approved
 * sites through (white lists) or the link is shut down. Checks then get rarer: 1 → 2 → 5 → 10 → 15 min,
 * ±20 % at random, until something answers.
 */
object SilentNetworkBackoff {
    val STEPS_MS = listOf(60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L)

    fun delayMs(round: Int, random: Random = Random.Default): Long {
        val base = STEPS_MS[(round.coerceAtLeast(1) - 1).coerceAtMost(STEPS_MS.size - 1)]
        val spread = base / 5
        return base + random.nextLong(-spread, spread + 1)
    }
}

/** Why the connection is held without a restart; drives one plain status line on both platforms. */
enum class LinkHold {
    /** Replies are slow or missing, but nothing proves the config is dead: the tunnel and live connections stay. */
    JAMMED,

    /** No server answers while the network is there: possibly white lists or a shutdown. Rare checks. */
    ALL_SILENT,
}

/** One journal line per core restart or config switch, the same on both platforms. */
object RestartLog {
    fun line(reason: String, attempt: Int, delayMs: Long, detail: String = ""): String =
        "restart reason=$reason attempt=$attempt delay=${delayMs}ms" + detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
}
