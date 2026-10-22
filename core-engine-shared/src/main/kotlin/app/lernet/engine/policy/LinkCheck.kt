package app.lernet.engine.policy

/**
 * «Проверить связь» — one algorithm for Android and Windows, on top of the handshake budget.
 *
 * Every step that opens a connection takes the budget first (of the active server when it goes through the
 * VPN, of the checked server when it goes straight to it), and checks run one by one with a random pause
 * (BackupRotation.buttonGapMs). No step starts a second core: a full path through another config is checked
 * when the user switches or connects to it, so the button never adds a full handshake from this network.
 */
object LinkCheck {
    /** One step's limit; the same as the tunnel checks of the monitor. */
    const val TIMEOUT_MS: Int = TunnelProbeSchedule.CHECK_TIMEOUT_MS.toInt()

    /** How «До сервера» is measured. */
    enum class Server {
        /** The running config: the value measured by the connection itself; nothing new is opened. */
        LIVE,

        /** A TCP connection to the server through the running VPN; this network sees nothing new. */
        THROUGH_VPN,

        /** A TCP connection straight to the server (VPN off, or a UDP config the VPN cannot CONNECT to). */
        DIRECT,
    }

    /** How «Интернет через VPN» is measured. */
    enum class FullPath {
        /** One fresh request to the check address through the running VPN. */
        RUNNING_VPN,

        /** Not measured now: the full path of another config is checked on switching to it. */
        ON_SWITCH,

        /** Not measured now: checked when the user connects. */
        ON_CONNECT,
    }

    data class Plan(val server: Server, val fullPath: FullPath)

    fun plan(active: Boolean, vpnRunning: Boolean, tcp: Boolean): Plan = when {
        active && vpnRunning -> Plan(Server.LIVE, FullPath.RUNNING_VPN)
        vpnRunning -> Plan(
            if (BackupReach.route(tunnelUp = true, tcp = tcp, userAsked = true) == ReachRoute.TUNNEL) Server.THROUGH_VPN else Server.DIRECT,
            FullPath.ON_SWITCH,
        )
        else -> Plan(Server.DIRECT, FullPath.ON_CONNECT)
    }

    /** The words under «Интернет через VPN» when it was not measured, the same on both platforms. */
    fun notMeasured(fullPath: FullPath): String? = when (fullPath) {
        FullPath.RUNNING_VPN -> null
        FullPath.ON_SWITCH -> "Проверим при переключении"
        FullPath.ON_CONNECT -> "Проверим при подключении"
    }
}
