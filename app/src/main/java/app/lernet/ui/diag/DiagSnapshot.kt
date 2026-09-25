package app.lernet.ui.diag

import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveConnStatus
import java.util.Locale

/** Only presents fields actually returned by the connection event or explicitly captured. */
object DiagSnapshot {
    enum class Wire { TLS, CLEARTEXT, UNKNOWN }

    data class Model(
        val dest: String,
        val domain: String?,
        val protocol: String?,
        val transport: String?,
        val ipVersion: Int?,
        val rule: String?,
        val routeChain: List<String>,
        val pipe: String,
        val status: LiveConnStatus,
        val uplink: Long,
        val downlink: Long,
        val uid: Int?,
        val pid: Long?,
        val requestLine: String?,
        val headers: String?,
        val body: String?,
        val bodyTruncated: Boolean,
        val wire: Wire,
    ) {
        val hasCapturedContent: Boolean get() = requestLine != null || headers != null || body != null
    }

    fun of(row: LiveConn): Model = Model(
        dest = row.dest,
        domain = row.domain?.trim()?.takeIf { it.isNotEmpty() },
        protocol = row.protocol?.trim()?.takeIf { it.isNotEmpty() },
        transport = row.transport?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT),
        ipVersion = row.ipVersion?.takeIf { it == 4 || it == 6 },
        rule = row.rule?.trim()?.takeIf { it.isNotEmpty() },
        routeChain = row.routeChain.filter { it.isNotBlank() },
        pipe = row.pipeLabel,
        status = row.status,
        uplink = row.uplink,
        downlink = row.downlink,
        uid = row.uid,
        pid = row.pid,
        requestLine = row.method?.trim()?.takeIf { it.isNotEmpty() },
        headers = row.headers?.trim()?.takeIf { it.isNotEmpty() },
        body = row.body?.takeIf { it.isNotBlank() }?.let { value ->
            if (value.length <= MAX_DISPLAY_BODY_CHARS) value else value.take(MAX_DISPLAY_BODY_CHARS) + "…"
        },
        bodyTruncated = (row.body?.length ?: 0) > MAX_DISPLAY_BODY_CHARS,
        wire = wireOf(row.protocol),
    )

    fun wireOf(protocol: String?): Wire {
        val raw = protocol?.trim()?.lowercase().orEmpty()
        return when {
            "tls" in raw || "https" in raw || "quic" in raw -> Wire.TLS
            raw == "http" || raw == "http/1.1" || raw == "http/1.0" || raw == "h2c" -> Wire.CLEARTEXT
            else -> Wire.UNKNOWN
        }
    }

    private const val MAX_DISPLAY_BODY_CHARS = 16_384
}
