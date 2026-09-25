package app.lernet.vpn

import app.lernet.engine.log.CrashTrail
import app.lernet.engine.net.OutboundDialer
import app.lernet.engine.net.OutboundEndpoint
import app.lernet.engine.net.VpnGuard
import app.lernet.engine.redact.LerNetLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidProtectedDialer : OutboundDialer {
    override suspend fun dial(target: OutboundEndpoint, timeoutMs: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching { dialBlocking(target, timeoutMs) }
        }

    private fun dialBlocking(target: OutboundEndpoint, timeoutMs: Int) {
        val vpn = VpnRuntime.current()
        val network = DefaultNetworkMonitor.underlyingNetwork()
        val socket = Socket()
        try {
            if (network != null) {
                network.bindSocket(socket)
                LerNetLog.i(TAG, "probe bindSocket underlay=$network")
            } else {
                LerNetLog.w(TAG, "probe has no underlying Network; relying on protect() only")
            }
            if (vpn != null) {
                val ok = vpn.protect(socket)
                LerNetLog.i(TAG, "probe protect(${target.label()}) ok=$ok")
                if (!ok) {
                    val crumb = "protect(probe) FAILED → Failed"
                    CrashTrail.mark("$crumb ${target.label()}")
                    VpnRuntime.signalRevoked(crumb)
                }
                VpnGuard.requireProtect(ok)
            } else {
                LerNetLog.i(TAG, "probe without VpnService (proxy mode) ${target.label()}")
            }
            val resolved = if (network != null) {
                network.getAllByName(target.host).firstOrNull()
                    ?: error("underlay DNS empty for ${target.host}")
            } else {
                InetAddress.getByName(target.host)
            }
            LerNetLog.i(TAG, "probe resolve ${target.host} -> ${resolved.hostAddress} via ${if (network != null) "underlay" else "system"}")
            socket.connect(InetSocketAddress(resolved, target.port), timeoutMs)
            LerNetLog.i(TAG, "probe TCP ${target.label()} (${resolved.hostAddress}) connected")
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "LerNet.Probe"
    }
}
