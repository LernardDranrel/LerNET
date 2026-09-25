package app.lernet.engine

enum class HostTarget {
    VPN,
    PROXY,
}

enum class HostAction {
    START_FOREGROUND,
    SIGNAL_STOP,
    STOP_SERVICE,
}

data class HostCommand(
    val target: HostTarget,
    val action: HostAction,
)

object EngineHostPlanner {
    fun start(
        mode: RunMode,
        vpnRunning: Boolean,
        proxyRunning: Boolean,
    ): List<HostCommand> =
        when (mode) {
            RunMode.FULL_VPN -> stop(HostTarget.PROXY, proxyRunning) +
                listOf(HostCommand(HostTarget.VPN, HostAction.START_FOREGROUND))
            RunMode.PROXY -> stop(HostTarget.VPN, vpnRunning) +
                listOf(HostCommand(HostTarget.PROXY, HostAction.START_FOREGROUND))
        }

    fun stopAll(
        vpnRunning: Boolean,
        proxyRunning: Boolean,
    ): List<HostCommand> = stop(HostTarget.VPN, vpnRunning) + stop(HostTarget.PROXY, proxyRunning)

    fun stop(
        target: HostTarget,
        running: Boolean,
    ): List<HostCommand> =
        if (running) {
            listOf(
                HostCommand(target, HostAction.SIGNAL_STOP),
                HostCommand(target, HostAction.STOP_SERVICE),
            )
        } else {
            listOf(HostCommand(target, HostAction.STOP_SERVICE))
        }
}
