package app.lernet.engine.net

import app.lernet.config.net.LocalProxyAccess
import app.lernet.config.net.LocalProxyHttp
import app.lernet.engine.policy.BackupReach
import app.lernet.engine.policy.ReachRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One reachability check of a backup server; null result = cannot be checked this way now. */
fun interface BackupProbe {
    suspend fun check(endpoint: OutboundEndpoint, tunnel: LocalProxyAccess?, userAsked: Boolean): BackupProbeResult?
}

data class BackupProbeResult(val ok: Boolean, val latencyMs: Long?, val throughTunnel: Boolean)

/** Follows [BackupReach]: dials the host directly; never a meaningless CONNECT through the live tunnel. */
class TunnelFirstBackupProbe(private val direct: OutboundDialer, private val timeoutMs: Int = 8_000) : BackupProbe {
    override suspend fun check(endpoint: OutboundEndpoint, tunnel: LocalProxyAccess?, userAsked: Boolean): BackupProbeResult? {
        val route = BackupReach.route(tunnel != null, endpoint.transport == ProbeTransport.TCP, userAsked)
        val started = System.nanoTime()
        fun elapsed() = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1)
        return when (route) {
            ReachRoute.SKIP -> null
            ReachRoute.TUNNEL -> withContext(Dispatchers.IO) {
                val ok = runCatching { LocalProxyHttp.connectTunnel(tunnel!!, endpoint.host, endpoint.port, timeoutMs) }.isSuccess
                BackupProbeResult(ok, if (ok) elapsed() else null, throughTunnel = true)
            }
            ReachRoute.DIRECT -> {
                val ok = runCatching { direct.dial(endpoint, timeoutMs).isSuccess }.getOrDefault(false)
                BackupProbeResult(ok, if (ok) elapsed() else null, throughTunnel = false)
            }
        }
    }
}
