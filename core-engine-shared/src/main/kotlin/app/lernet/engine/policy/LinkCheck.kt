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

    /**
     * Щадящая — по умолчанию, без второго ядра и лишнего рукопожатия.
     * Полная — только осознанно: поднимает временное ядро / полный путь (лишнее рукопожатие ради диагностики).
     */
    enum class Intensity {
        GENTLE,
        FULL,
    }

    const val GENTLE_LABEL = "Проверить"
    const val FULL_LABEL = "Полная проверка"
    const val FULL_CONFIRM_TITLE = "Полная проверка пути?"
    const val FULL_CONFIRM_BODY =
        "Откроется отдельное подключение к серверу (ещё одно рукопожатие). " +
            "Нужно только для диагностики, когда щадящей проверки мало. Продолжить?"
    const val FULL_CONFIRM = "Проверить полностью"

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

        /** Conscious full check: temporary core / full path even with VPN off (extra handshake). */
        DIAGNOSTIC,
    }

    data class Plan(val server: Server, val fullPath: FullPath)

    fun plan(active: Boolean, vpnRunning: Boolean, tcp: Boolean, intensity: Intensity = Intensity.GENTLE): Plan = when {
        active && vpnRunning -> Plan(Server.LIVE, FullPath.RUNNING_VPN)
        intensity == Intensity.FULL && !vpnRunning -> Plan(Server.DIRECT, FullPath.DIAGNOSTIC)
        vpnRunning -> Plan(
            if (BackupReach.route(tunnelUp = true, tcp = tcp, userAsked = true) == ReachRoute.TUNNEL) Server.THROUGH_VPN else Server.DIRECT,
            FullPath.ON_SWITCH,
        )
        else -> Plan(Server.DIRECT, FullPath.ON_CONNECT)
    }

    /** The words under «Интернет через VPN» when it was not measured, the same on both platforms. */
    fun notMeasured(fullPath: FullPath): String? = when (fullPath) {
        FullPath.RUNNING_VPN, FullPath.DIAGNOSTIC -> null
        FullPath.ON_SWITCH -> "Проверим при переключении"
        FullPath.ON_CONNECT -> "Проверим при подключении"
    }

    /** Honest label under the full-path row for the chosen intensity. */
    fun fullPathHint(fullPath: FullPath, intensity: Intensity): String? = when {
        fullPath == FullPath.DIAGNOSTIC -> "полная проверка · отдельное рукопожатие"
        intensity == Intensity.GENTLE -> notMeasured(fullPath)
        else -> notMeasured(fullPath)
    }
}
