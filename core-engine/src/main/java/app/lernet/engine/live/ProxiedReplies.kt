package app.lernet.engine.live

/**
 * Counts replies that came back through the VPN exit only. Direct connections (sites the rules
 * send past the tunnel) never count: their bytes prove nothing about the config.
 */
class ProxiedReplies {
    private val seen = HashMap<String, Long>()

    /** Monotonic count of observed reply growth on proxied connections. */
    var total: Long = 0
        private set

    /** Feeds one batch of connection rows; returns true when a proxied connection received new bytes. */
    fun observe(reset: Boolean, rows: List<LiveConn>): Boolean {
        if (reset) {
            val alive = rows.mapTo(HashSet()) { it.id }
            seen.keys.retainAll(alive)
        }
        var grew = false
        rows.forEach { row ->
            if (row.via != LiveVia.PROXY) return@forEach
            val before = seen[row.id] ?: 0L
            if (row.downlink > before) {
                grew = true
                total += 1
            }
            if (row.status == LiveConnStatus.OPEN) seen[row.id] = maxOf(before, row.downlink) else seen.remove(row.id)
        }
        if (seen.size > MAX_TRACKED) seen.clear()
        return grew
    }

    fun clear() {
        seen.clear()
    }

    private companion object {
        const val MAX_TRACKED = 4_096
    }
}
