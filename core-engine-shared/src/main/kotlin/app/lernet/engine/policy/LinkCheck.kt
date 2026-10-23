package app.lernet.engine.policy

/**
 * «Проверить связь» — one algorithm for Android and Windows, on top of the handshake budget.
 *
 * Every step that opens a connection takes the budget first (of the active server when it goes through the
 * VPN, of the checked server when it goes straight to it), and checks run one by one with a random pause
 * (BackupRotation.buttonGapMs). No gentle step starts a second core: a full path through another config is
 * measured only by a conscious «Полная проверка» (DIAGNOSTIC) or when the user switches/connects to it.
 *
 * With the VPN on, «До сервера» for a non-active config is always a direct dial (outside the tunnel).
 * Checking another config through the live tunnel would only prove the *active* path still works.
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

        /**
         * Legacy: TCP CONNECT through the running VPN to another config's host.
         * Not chosen by [plan] any more — that path only proved the active tunnel, not the backup.
         */
        THROUGH_VPN,

        /** A TCP connection straight to the server (outside the tunnel). */
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

        /** Conscious full check: temporary core / full path for this config (extra handshake). */
        DIAGNOSTIC,
    }

    data class Plan(val server: Server, val fullPath: FullPath)

    /**
     * @param tcp kept so Android and Windows call sites stay identical; UDP still cannot CONNECT-preflight
     *   and falls through the same DIRECT / ON_SWITCH words.
     */
    @Suppress("UNUSED_PARAMETER")
    fun plan(active: Boolean, vpnRunning: Boolean, tcp: Boolean, intensity: Intensity = Intensity.GENTLE): Plan = when {
        // Active config may use the live tunnel even for a full press — nothing else is more honest.
        active && vpnRunning -> Plan(Server.LIVE, FullPath.RUNNING_VPN)
        // Conscious full check of any other config (VPN on or off): separate path, not the live tunnel.
        intensity == Intensity.FULL -> Plan(Server.DIRECT, FullPath.DIAGNOSTIC)
        // VPN on, another config: «До сервера» straight to the host; full path waits for switch / full button.
        vpnRunning -> Plan(Server.DIRECT, FullPath.ON_SWITCH)
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
