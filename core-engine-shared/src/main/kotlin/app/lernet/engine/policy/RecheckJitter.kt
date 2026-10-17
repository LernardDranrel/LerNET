package app.lernet.engine.policy

import kotlin.random.Random

/**
 * When to look at an unavailable config again: a fresh random pause every time,
 * whole seconds between [MIN_SECONDS] (45 s) and [MAX_SECONDS] (5 min), e.g. 1:00, 4:52, 3:07.
 * Production uses the platform generator ([Random.Default], seeded from the system), never a fixed seed,
 * so many devices behind one blocked server do not knock in step.
 */
object RecheckJitter {
    const val MIN_SECONDS = 45L
    const val MAX_SECONDS = 300L
    const val MIN_MS = MIN_SECONDS * 1_000
    const val MAX_MS = MAX_SECONDS * 1_000

    fun nextMs(random: Random = Random.Default, atLeastMs: Long = MIN_MS): Long {
        val lowSeconds = ((atLeastMs + 999) / 1_000).coerceIn(MIN_SECONDS, MAX_SECONDS)
        return random.nextLong(lowSeconds, MAX_SECONDS + 1) * 1_000
    }

    /** "4:52" for screens and the journal. */
    fun label(ms: Long): String {
        val total = ms / 1_000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
