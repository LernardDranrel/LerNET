package app.lernet.engine.policy

/** One connection as the core reports it; only connections through the VPN exit matter. */
data class FlowSample(
    val id: String,
    val proxied: Boolean,
    val uplink: Long,
    val downlink: Long,
    val open: Boolean = true,
)

enum class BlockingSign {
    /** A connection received ~16 KB and then nothing more, while we kept sending. */
    FROZEN_AFTER_16KB,

    /** The network cut the server connection right after our first TLS message. */
    RESET_AFTER_HELLO,
}

/**
 * Passive block detector. It only watches ordinary traffic and core log lines: no test file on
 * the server, no extra requests. Two known filter patterns:
 *
 * - "freeze after ~16 KB": a connection gets 8–32 KB of replies, then not one byte more for
 *   [stallMs], although our side keeps sending. One such connection proves nothing (a server can
 *   be slow); [flowsToFlag] different connections inside [windowMs] are a pattern.
 * - "reset right after the first TLS message": the core reports a TLS/Reality handshake that
 *   ended with a reset or EOF. [resetsToFlag] of them inside [windowMs].
 *
 * A dead server looks different (timeouts, refused connections, no bytes at all), so it does
 * not trigger either sign. Pure logic on a caller clock, shared by Android and Windows.
 */
class BlockingDetector(
    private val stallMs: Long = 10_000L,
    private val band: LongRange = 8_000L..32_000L,
    private val flowsToFlag: Int = 2,
    private val resetsToFlag: Int = 3,
    private val windowMs: Long = 60_000L,
) {
    private class Flow(var down: Long, var up: Long, var grewAt: Long, var upAtGrow: Long, var flagged: Boolean = false)

    private val flows = HashMap<String, Flow>()
    private val frozenAt = ArrayDeque<Long>()
    private val resetAt = ArrayDeque<Long>()
    private var reported = false

    /** Feeds the current connection list. Returns a sign once, when the pattern is reached. */
    fun onFlows(nowMs: Long, rows: List<FlowSample>, complete: Boolean = true): BlockingSign? {
        if (complete) {
            val alive = rows.mapTo(HashSet()) { it.id }
            flows.keys.retainAll(alive)
        }
        rows.forEach { row ->
            if (!row.proxied) return@forEach
            if (!row.open) {
                flows.remove(row.id)
                return@forEach
            }
            val flow = flows[row.id]
            if (flow == null) {
                flows[row.id] = Flow(row.downlink, row.uplink, nowMs, row.uplink)
                return@forEach
            }
            if (row.downlink > flow.down) {
                flow.down = row.downlink
                flow.grewAt = nowMs
                flow.upAtGrow = row.uplink
            }
            flow.up = maxOf(flow.up, row.uplink)
            if (!flow.flagged && flow.down in band && nowMs - flow.grewAt >= stallMs && flow.up > flow.upAtGrow) {
                flow.flagged = true
                frozenAt.addLast(nowMs)
            }
        }
        if (flows.size > MAX_TRACKED) flows.clear()
        trim(frozenAt, nowMs)
        return report(if (frozenAt.size >= flowsToFlag) BlockingSign.FROZEN_AFTER_16KB else null)
    }

    /** Feeds one core log line. */
    fun onLogLine(nowMs: Long, line: String): BlockingSign? {
        if (isHandshakeReset(line)) resetAt.addLast(nowMs)
        trim(resetAt, nowMs)
        return report(if (resetAt.size >= resetsToFlag) BlockingSign.RESET_AFTER_HELLO else null)
    }

    /** New config or new network: forget everything. */
    fun reset() {
        flows.clear()
        frozenAt.clear()
        resetAt.clear()
        reported = false
    }

    private fun report(sign: BlockingSign?): BlockingSign? {
        if (sign == null || reported) return null
        reported = true
        return sign
    }

    private fun trim(events: ArrayDeque<Long>, nowMs: Long) {
        while (events.isNotEmpty() && nowMs - events.first() > windowMs) events.removeFirst()
    }

    companion object {
        private const val MAX_TRACKED = 4_096

        internal fun isHandshakeReset(line: String): Boolean {
            val text = line.lowercase()
            if ("handshake" !in text && "reality" !in text) return false
            return "connection reset" in text || "reset by peer" in text ||
                "forcibly closed" in text || text.trimEnd().endsWith(": eof") || "unexpected eof" in text
        }

        /** Honest, region-neutral text for the user. */
        fun message(sign: BlockingSign): String = when (sign) {
            BlockingSign.FROZEN_AFTER_16KB ->
                "Похоже, сеть останавливает соединение с сервером после первых ~16 КБ. Так делают некоторые " +
                    "фильтры трафика. Переключаемся на другой конфиг. Если не поможет, нужен другой сервер или транспорт."
            BlockingSign.RESET_AFTER_HELLO ->
                "Сеть обрывает соединение с сервером сразу после начала TLS-рукопожатия. Похоже на фильтр трафика. " +
                    "Переключаемся на другой конфиг. Может помочь «Дробить первое сообщение TLS» в настройках."
        }
    }
}

/**
 * Monotonic counters of traffic through the VPN exit only, built from connection snapshots
 * (connections come and go, so plain sums would jump back). Direct traffic never counts: sites
 * the rules send past the tunnel say nothing about the tunnel.
 */
class ProxiedTraffic {
    private val seen = HashMap<String, LongArray>()

    var uplink: Long = 0
        private set

    /** Grows by one for every snapshot in which some proxied connection received new bytes. */
    var replies: Long = 0
        private set

    fun observe(rows: List<FlowSample>, complete: Boolean = true) {
        if (complete) {
            val alive = rows.mapTo(HashSet()) { it.id }
            seen.keys.retainAll(alive)
        }
        var grew = false
        rows.forEach { row ->
            if (!row.proxied) return@forEach
            val last = seen.getOrPut(row.id) { longArrayOf(0L, 0L) }
            if (row.uplink > last[0]) {
                uplink += row.uplink - last[0]
                last[0] = row.uplink
            }
            if (row.downlink > last[1]) {
                grew = true
                last[1] = row.downlink
            }
            if (!row.open) seen.remove(row.id)
        }
        if (grew) replies++
        if (seen.size > 4_096) seen.clear()
    }

    fun clear() {
        seen.clear()
    }
}
