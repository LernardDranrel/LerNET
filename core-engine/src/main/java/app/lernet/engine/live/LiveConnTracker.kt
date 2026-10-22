package app.lernet.engine.live

/**
 * Keeps the last full row of every open connection so byte updates can be turned into rows.
 *
 * The core sends a full connection only when it opens and when it closes; in between it sends
 * UPDATE events with the id and the byte deltas alone. Without this tracker those updates were
 * dropped, so the block detector and the reply counter never saw a connection that stalled
 * half-way (it looked like 0 bytes until it closed).
 */
class LiveConnTracker(private val maxTracked: Int = MAX_TRACKED) {
    private val rows = LinkedHashMap<String, LiveConn>()

    fun reset() {
        rows.clear()
    }

    /** A full row (NEW, or CLOSED with data): remembered while open, forgotten when [closed]. */
    fun onRow(row: LiveConn, closed: Boolean): LiveConn {
        if (closed) {
            rows.remove(row.id)
        } else {
            rows[row.id] = row
            if (rows.size > maxTracked) rows.remove(rows.keys.first())
        }
        return row
    }

    /** UPDATE without a row: the known row plus the deltas; null for a connection never seen in full. */
    fun onUpdate(id: String, uplinkDelta: Long, downlinkDelta: Long): LiveConn? {
        val known = rows[id] ?: return null
        val uplink = known.uplink + uplinkDelta.coerceAtLeast(0L)
        val downlink = known.downlink + downlinkDelta.coerceAtLeast(0L)
        val next = known.copy(uplink = uplink, downlink = downlink, status = openStatus(uplink, downlink))
        rows[id] = next
        return next
    }

    /** CLOSED without a row: the known row marked closed; null for a connection never seen in full. */
    fun onClosed(id: String): LiveConn? {
        val known = rows.remove(id) ?: return null
        val status = if (known.uplink > 0L && known.downlink == 0L) LiveConnStatus.UNFINISHED else LiveConnStatus.CLOSED
        return known.copy(status = status)
    }

    companion object {
        const val MAX_TRACKED = 2_048

        /** Same rule as the row mapper: sent but nothing received yet means unfinished. */
        fun openStatus(uplink: Long, downlink: Long): LiveConnStatus =
            if (uplink > 0L && downlink == 0L) LiveConnStatus.UNFINISHED else LiveConnStatus.OPEN
    }
}
