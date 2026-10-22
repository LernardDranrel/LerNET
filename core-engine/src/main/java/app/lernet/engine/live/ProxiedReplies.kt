package app.lernet.engine.live

/**
 * Counts replies that came back through the VPN exit only. Direct connections (sites the rules
 * send past the tunnel) never count: their bytes prove nothing about the config.
 */
class ProxiedReplies {
    private val seen = HashMap<String, Long>()
    private val sent = HashMap<String, Long>()

    /** Monotonic count of observed reply growth on proxied connections. */
    var total: Long = 0
        private set

    /**
     * Monotonic bytes sent on proxied connections. Pairs with [total] for the silence rule: the
     * tunnel's own counter also holds direct traffic, which proves nothing about the config.
     */
    var uplinkTotal: Long = 0
        private set

    /** Feeds one batch of connection rows; returns true when a proxied connection received new bytes. */
    fun observe(reset: Boolean, rows: List<LiveConn>): Boolean {
        if (reset) {
            val alive = rows.mapTo(HashSet()) { it.id }
            seen.keys.retainAll(alive)
            sent.keys.retainAll(alive)
        }
        var grew = false
        rows.forEach { row ->
            if (row.via != LiveVia.PROXY) return@forEach
            val before = seen[row.id] ?: 0L
            if (row.downlink > before) {
                grew = true
                total += 1
            }
            val sentBefore = sent[row.id] ?: 0L
            if (row.uplink > sentBefore) uplinkTotal += row.uplink - sentBefore
            if (row.status == LiveConnStatus.CLOSED) {
                seen.remove(row.id)
                sent.remove(row.id)
            } else {
                seen[row.id] = maxOf(before, row.downlink)
                sent[row.id] = maxOf(sentBefore, row.uplink)
            }
        }
        if (seen.size > MAX_TRACKED) seen.clear()
        if (sent.size > MAX_TRACKED) sent.clear()
        return grew
    }

    fun clear() {
        seen.clear()
        sent.clear()
    }

    private companion object {
        const val MAX_TRACKED = 4_096
    }
}
