package app.lernet.vpn

import android.net.VpnService
import app.lernet.engine.log.CrashTrail
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicReference

object VpnRuntime {
    private val service = AtomicReference<VpnService?>(null)

    @Volatile
    private var revokeSink: (() -> Unit)? = null

    fun attach(vpn: VpnService) {
        service.set(vpn)
    }

    fun detach(vpn: VpnService) {
        service.compareAndSet(vpn, null)
    }

    fun setRevokeSink(sink: (() -> Unit)?) {
        revokeSink = sink
    }

    fun current(): VpnService? = service.get()

    /**
     * Failed UI even if gomobile swallows the checked Exception from protect/openTun.
     * [revokeSink] is [app.lernet.engine.LibboxBoxEngine.notifyRevoked].
     */
    fun signalRevoked(crumb: String) {
        CrashTrail.mark(crumb)
        runCatching { revokeSink?.invoke() }
            .onFailure { error ->
                CrashTrail.recordFailure("VpnRuntime.signalRevoked", error)
            }
    }

    fun protectFd(fd: Int): Boolean {
        val vpn = service.get() ?: return false
        return vpn.protect(fd)
    }

    fun protectSocket(socket: java.net.Socket): Boolean {
        val vpn = service.get() ?: return false
        return vpn.protect(socket)
    }

    /** No VPN yet: the registry query uses the current network. A failed protect omits ASN. */
    fun protectDatagram(socket: DatagramSocket): Boolean {
        val vpn = service.get() ?: return true
        return vpn.protect(socket)
    }
}
