package app.lernet.engine.live

import app.lernet.engine.TrafficDisplay

enum class LiveVia {
    PROXY,
    DIRECT,
    OTHER,
    ;

    companion object {
        fun of(outbound: String, outboundType: String): LiveVia {
            val tag = outbound.trim().lowercase()
            val type = outboundType.trim().lowercase()
            return when {
                tag == "direct" || type == "direct" -> DIRECT
                tag == "proxy" || type == "vless" || type == "vmess" || type == "trojan" || type == "shadowsocks" ->
                    PROXY
                tag.startsWith("pipe-") -> PROXY
                else -> OTHER
            }
        }
    }
}

enum class LiveConnStatus {
    OPEN,
    CLOSED,
    UNFINISHED,
}

data class LiveConn(
    val id: String,
    val app: String,
    val uid: Int?,
    val destHost: String,
    val destPort: Int,
    val domain: String?,
    val outbound: String,
    val via: LiveVia,
    val uplink: Long,
    val downlink: Long,
    val rule: String? = null,
    val status: LiveConnStatus = LiveConnStatus.OPEN,
    val method: String? = null,
    val protocol: String? = null,
    val headers: String? = null,
    val body: String? = null,
    val pid: Long? = null,
    /** Native connection creation time; only used to keep reset snapshots chronological. */
    val createdAt: Long = 0L,
    val transport: String? = null,
    val ipVersion: Int? = null,
    val routeChain: List<String> = emptyList(),
) {
    val dest: String get() = "$destHost:$destPort"

    val pipeLabel: String
        get() {
            val tag = outbound.trim()
            return when {
                tag.startsWith("pipe-") -> tag.removePrefix("pipe-").ifBlank { tag }
                via == LiveVia.DIRECT -> "direct"
                via == LiveVia.PROXY -> if (tag.isBlank() || tag == "proxy") "proxy" else tag
                else -> tag.ifBlank { "other" }
            }
        }
}

object LiveConnFormat {
    fun line(conn: LiveConn): String {
        val app = buildString {
            append(conn.app.ifBlank { "?" })
            conn.uid?.let { append("/").append(it) }
        }
        val rule = conn.rule?.takeIf { it.isNotBlank() } ?: "—"
        val host = conn.domain?.takeIf { it.isNotBlank() } ?: conn.dest
        val status = when (conn.status) {
            LiveConnStatus.OPEN -> "open"
            LiveConnStatus.CLOSED -> "ok"
            LiveConnStatus.UNFINISHED -> "no reply"
        }
        return "$app → $host → $rule → ${conn.pipeLabel} → $status " +
            "↑${TrafficDisplay.formatBytes(conn.uplink)} ↓${TrafficDisplay.formatBytes(conn.downlink)}"
    }

    fun unfinished(conn: LiveConn): Boolean =
        conn.status == LiveConnStatus.UNFINISHED ||
            (conn.uplink > 0L && conn.downlink == 0L && conn.status != LiveConnStatus.CLOSED)
}
