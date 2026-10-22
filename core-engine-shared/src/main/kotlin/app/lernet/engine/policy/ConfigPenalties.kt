package app.lernet.engine.policy

import kotlin.random.Random

/**
 * Configs that died, with the time they rest: 2 → 10 → 30 min after the first, second and every later
 * failure, each pause ±20 % so many phones behind one server do not knock in step.
 *
 * Unlike the old «known dead» set this list is not cleared when a config starts, so a folder cannot
 * swing A → B → A without a pause, and it survives an app restart ([encode]/[decode]). A config that
 * worked again leaves the list. A new network lets every resting config be tried once more, but the
 * level stays: if it fails there too, the next pause is longer.
 *
 * Pure logic on a caller-supplied wall clock, shared by Android and Windows; thread-safe.
 */
class ConfigPenalties(
    private val ladderMs: List<Long> = LADDER_MS,
    private val random: Random = Random.Default,
) {
    data class Entry(val level: Int, val untilMs: Long, val failedAtMs: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private var lastNetworkReleaseAt = Long.MIN_VALUE

    /** Called after every change, for persistence. */
    @Volatile var onChange: ((String) -> Unit)? = null

    /** The config failed: it rests for the next pause of the ladder. */
    fun fail(id: String, nowMs: Long): Entry {
        val entry = synchronized(this) {
            val level = ((entries[id]?.level ?: 0) + 1).coerceAtMost(ladderMs.size)
            Entry(level, nowMs + jittered(ladderMs[level - 1]), nowMs).also { entries[id] = it }
        }
        notifyChange()
        return entry
    }

    /** The config worked: it leaves the list. */
    fun clear(id: String) {
        val removed = synchronized(this) { entries.remove(id) != null }
        if (removed) notifyChange()
    }

    @Synchronized
    fun isResting(id: String, nowMs: Long): Boolean = (entries[id]?.untilMs ?: 0L) > nowMs

    @Synchronized
    fun restMs(id: String, nowMs: Long): Long = ((entries[id]?.untilMs ?: 0L) - nowMs).coerceAtLeast(0L)

    @Synchronized
    fun entry(id: String): Entry? = entries[id]

    @Synchronized
    fun resting(nowMs: Long): Set<String> = entries.filterValues { it.untilMs > nowMs }.keys.toSet()

    /** Soonest moment one of [ids] may be tried again; null when none of them rests. */
    @Synchronized
    fun nextFreeAt(ids: Collection<String>, nowMs: Long): Long? =
        ids.mapNotNull { entries[it]?.untilMs?.takeIf { until -> until > nowMs } }.minOrNull()

    /**
     * The device moved to another network: resting configs may be tried once more now; the level stays.
     * Rate-limited to once per [NETWORK_RELEASE_GAP_MS], because a phone reports small changes often.
     */
    fun onNetworkChanged(nowMs: Long): Boolean {
        val changed = synchronized(this) {
            if (lastNetworkReleaseAt != Long.MIN_VALUE && nowMs - lastNetworkReleaseAt < NETWORK_RELEASE_GAP_MS) return false
            lastNetworkReleaseAt = nowMs
            var any = false
            entries.replaceAll { _, entry ->
                if (entry.untilMs > nowMs) {
                    any = true
                    entry.copy(untilMs = nowMs)
                } else {
                    entry
                }
            }
            any
        }
        if (changed) notifyChange()
        return changed
    }

    /** One line per config: `id level untilMs failedAtMs`. */
    @Synchronized
    fun encode(): String = entries.entries.joinToString("\n") { (id, e) -> "$id ${e.level} ${e.untilMs} ${e.failedAtMs}" }

    /** Loads [encode] output; entries older than [FORGET_MS] are dropped, a broken line is skipped. */
    fun decode(raw: String?, nowMs: Long) {
        synchronized(this) {
            entries.clear()
            raw.orEmpty().lineSequence().forEach { line ->
                val parts = line.trim().split(' ')
                if (parts.size != 4) return@forEach
                val level = parts[1].toIntOrNull()?.coerceIn(1, ladderMs.size) ?: return@forEach
                val until = parts[2].toLongOrNull() ?: return@forEach
                val failedAt = parts[3].toLongOrNull() ?: return@forEach
                if (parts[0].isBlank() || nowMs - failedAt > FORGET_MS) return@forEach
                // A clock that jumped back must not park a config for longer than the top of the ladder.
                entries[parts[0]] = Entry(level, until.coerceAtMost(nowMs + ladderMs.last() * 2), failedAt)
            }
        }
    }

    private fun jittered(ms: Long): Long {
        val spread = ms / 5
        return ms + random.nextLong(-spread, spread + 1)
    }

    private fun notifyChange() {
        val listener = onChange ?: return
        listener(encode())
    }

    companion object {
        val LADDER_MS = listOf(2 * 60_000L, 10 * 60_000L, 30 * 60_000L)
        const val NETWORK_RELEASE_GAP_MS = 60_000L
        const val FORGET_MS = 24 * 60 * 60_000L
    }
}
