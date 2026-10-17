package app.lernet.vpn

import app.lernet.engine.log.CrashTrail
import app.lernet.engine.net.OutboundDialer
import app.lernet.engine.net.OutboundEndpoint
import app.lernet.engine.net.ProbeTransport
import app.lernet.engine.net.QuicVersionProbe
import app.lernet.engine.net.VpnGuard
import app.lernet.engine.redact.LerNetLog
import java.net.DatagramPacket
import java.net.DatagramSocket
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
        when (target.transport) {
            ProbeTransport.TCP -> Unit
            ProbeTransport.QUIC -> return quicBlocking(target, timeoutMs)
            ProbeTransport.UNVERIFIABLE -> {
                // A silent UDP server cannot be told from a dead one in advance; the tunnel check after
                // switching decides. Not a TCP connect, which would always fail for these configs.
                LerNetLog.i(TAG, "probe ${target.label()} skipped: UDP config cannot be checked in advance")
                return
            }
        }
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

    /** QUIC configs (hysteria2, tuic): the server must answer a version-negotiation request over UDP. */
    private fun quicBlocking(target: OutboundEndpoint, timeoutMs: Int) {
        val network = DefaultNetworkMonitor.underlyingNetwork()
        val socket = DatagramSocket()
        try {
            network?.bindSocket(socket)
            VpnRuntime.current()?.let { vpn ->
                val ok = vpn.protect(socket)
                LerNetLog.i(TAG, "probe protect udp(${target.label()}) ok=$ok")
                VpnGuard.requireProtect(ok)
            }
            val resolved = if (network != null) {
                network.getAllByName(target.host).firstOrNull() ?: error("underlay DNS empty for ${target.host}")
            } else {
                InetAddress.getByName(target.host)
            }
            val request = QuicVersionProbe.request()
            val reply = ByteArray(1_500)
            val deadline = System.currentTimeMillis() + timeoutMs
            var attempt = 0
            while (System.currentTimeMillis() < deadline) {
                socket.send(DatagramPacket(request, request.size, InetSocketAddress(resolved, target.port)))
                attempt++
                socket.soTimeout = minOf(1_000L, (deadline - System.currentTimeMillis()).coerceAtLeast(1L)).toInt()
                val packet = DatagramPacket(reply, reply.size)
                val got = runCatching { socket.receive(packet) }.isSuccess
                if (got && QuicVersionProbe.isVersionNegotiation(reply, packet.length)) {
                    LerNetLog.i(TAG, "probe QUIC ${target.label()} answered after $attempt send(s)")
                    return
                }
            }
            error("QUIC server ${target.label()} did not answer")
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val TAG = "LerNet.Probe"
    }
}
