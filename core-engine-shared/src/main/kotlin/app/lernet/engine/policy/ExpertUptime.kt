package app.lernet.engine.policy

/** «Без перерыва» counter next to the Expert lever: how long the shared TUN has stayed up. */
object ExpertUptime {
    /** Null when the tunnel is not running or the start time is unknown — nothing is invented. */
    fun label(runtime: ExpertRuntimeState, nowEpochMs: Long): String? {
        val since = runtime.runningSinceEpochMs ?: return null
        if (!ExpertPult.showsUptime(ExpertPult.status(runtime))) return null
        return format((nowEpochMs - since).coerceAtLeast(0))
    }

    fun format(elapsedMs: Long): String {
        val minutes = elapsedMs / 60_000
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "$days дн ${hours.toString().padStart(2, '0')} ч"
            hours > 0 -> "$hours ч ${mins.toString().padStart(2, '0')} мин"
            minutes > 0 -> "$mins мин"
            else -> "меньше минуты"
        }
    }
}
