package app.lernet.engine.net

/**
 * Android P+ treats the VPN as the default network. Publishing that
 * interface to libbox makes auto_detect_interface bind outbound sockets
 * to TUN — packets re-enter the tunnel and die (0 B/s, UI still Connected).
 *
 * SFA comment: registerDefaultNetworkCallback returns the VPN since P DP1.
 * Only a non-VPN underlay may be advertised as the default egress.
 */
object UnderlyingNetworkPolicy {
    fun isUsableDefault(
        interfaceName: String?,
        tunName: String?,
        vpnTransport: Boolean,
    ): Boolean {
        if (interfaceName.isNullOrBlank()) return false
        if (vpnTransport) return false
        if (!tunName.isNullOrBlank() && interfaceName == tunName) return false
        return true
    }

    fun shouldExposeToLibbox(
        interfaceName: String?,
        tunName: String?,
        vpnTransport: Boolean,
    ): Boolean = isUsableDefault(interfaceName, tunName, vpnTransport)
}
