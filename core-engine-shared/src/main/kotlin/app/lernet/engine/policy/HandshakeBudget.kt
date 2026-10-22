package app.lernet.engine.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One server and the name LerNET shows the filter in the TLS handshake (SNI): the unit the filter counts on. */
data class HandshakeKey(val host: String, val port: Int, val sni: String) {
    override fun toString(): String = "$host:$port/$sni"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** From a sing-box outbound; SNI falls back to the server name. Null for an outbound without a server. */
        fun fromOutboundJson(raw: String?): HandshakeKey? {
            val obj = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
            return fromOutbound(obj)
        }

        fun fromOutbound(obj: JsonObject): HandshakeKey? {
            val host = obj["server"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (host.isEmpty()) return null
            val port = obj["server_port"]?.jsonPrimitive?.intOrNull ?: 443
            val tls = obj["tls"] as? JsonObject
            val sni = tls?.get("server_name")?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() } ?: host
            return HandshakeKey(host, port, sni)
        }
    }
}

/**
 * At most [limit] new TLS handshakes that LerNET itself starts per server+SNI in [windowMs].
 *
 * Reports from the field (2026-10) say the filter freezes a server+SNI for minutes after more than
 * three handshakes in a minute. LerNET's own new connections count here: core starts, tunnel checks
 * that open a new connection, checks of backups and folder candidates, «Проверить все». Connections
 * that apps open through the running core are not ours to schedule and are not counted.
 *
 * Pure logic on a caller-supplied clock, shared by Android and Windows; thread-safe.
 */
class HandshakeBudget(
    private val limit: Int = LIMIT,
    private val windowMs: Long = WINDOW_MS,
) {
    private val spent = HashMap<HandshakeKey, ArrayDeque<Long>>()

    /** Time until one more handshake fits; 0 when it fits now. */
    @Synchronized
    fun waitMs(key: HandshakeKey, nowMs: Long): Long {
        val times = trimmed(key, nowMs) ?: return 0L
        if (times.size < limit) return 0L
        return (times.first() + windowMs - nowMs).coerceAtLeast(1L)
    }

    /** Takes a slot when one is free; false means wait [waitMs] first. */
    @Synchronized
    fun tryTake(key: HandshakeKey, nowMs: Long): Boolean {
        if (waitMs(key, nowMs) > 0L) return false
        spent.getOrPut(key) { ArrayDeque() }.addLast(nowMs)
        return true
    }

    /** Records a handshake that happens anyway (a core start the user asked for). */
    @Synchronized
    fun record(key: HandshakeKey, nowMs: Long) {
        trimmed(key, nowMs)
        spent.getOrPut(key) { ArrayDeque() }.addLast(nowMs)
    }

    @Synchronized
    fun used(key: HandshakeKey, nowMs: Long): Int = trimmed(key, nowMs)?.size ?: 0

    private fun trimmed(key: HandshakeKey, nowMs: Long): ArrayDeque<Long>? {
        val times = spent[key] ?: return null
        while (times.isNotEmpty() && nowMs - times.first() >= windowMs) times.removeFirst()
        if (times.isEmpty()) {
            spent.remove(key)
            return null
        }
        return times
    }

    companion object {
        const val LIMIT = 3
        const val WINDOW_MS = 60_000L
    }
}
