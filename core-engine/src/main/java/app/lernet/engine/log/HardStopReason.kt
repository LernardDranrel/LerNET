package app.lernet.engine.log

import app.lernet.engine.ConnectionCause

object HardStopReason {
    fun crumb(cause: ConnectionCause): String = "HARD_STOP reason=${tag(cause)}"

    fun tag(cause: ConnectionCause): String =
        when (cause) {
            ConnectionCause.UserDisconnected -> "user"
            is ConnectionCause.WatchdogTimeout -> "watchdog"
            is ConnectionCause.DnsStalled,
            is ConnectionCause.DnsUnreachable,
            -> "dns"
            is ConnectionCause.ConnectTimeout -> "connect-timeout"
            ConnectionCause.ServiceRevoked -> "revoked"
            ConnectionCause.NoActiveProfile -> "no-profile"
            is ConnectionCause.InvalidRouteTree -> "invalid-route"
            is ConnectionCause.InvalidConfig -> "invalid-config"
            ConnectionCause.EngineUnavailable -> "engine-unavailable"
            ConnectionCause.VpnPermissionDenied -> "vpn-permission"
            is ConnectionCause.EngineStartFailed -> "engine-start"
            is ConnectionCause.TlsFailure -> "tls"
            is ConnectionCause.HandshakeFailure -> "handshake"
            is ConnectionCause.ConnectionReset -> "reset"
            is ConnectionCause.DialFailure -> "dial"
            is ConnectionCause.DialTimeout -> "dial-timeout"
            is ConnectionCause.OutboundUnreachable -> "outbound"
            is ConnectionCause.ReconnectExhausted -> "reconnect-exhausted"
            is ConnectionCause.FailoverExhausted -> "failover-exhausted"
        }
}
