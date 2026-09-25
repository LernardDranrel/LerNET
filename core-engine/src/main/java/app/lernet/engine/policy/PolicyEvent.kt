package app.lernet.engine.policy

import app.lernet.engine.ConnectionCause
import app.lernet.engine.RunMode

sealed class PolicyEvent {
    data class StartRequested(
        val profileId: String,
        val outboundId: String,
        val mode: RunMode,
        val compiledJson: String,
    ) : PolicyEvent()

    data object EngineStarted : PolicyEvent()

    data class EngineStatus(
        val uplinkBps: Long,
        val downlinkBps: Long,
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val connectionsOut: Int,
        val dnsOk: Boolean = false,
    ) : PolicyEvent()

    data class EngineFailed(val cause: ConnectionCause) : PolicyEvent()

    data object WatchdogMiss : PolicyEvent()

    data object DnsHealthTimeout : PolicyEvent()

    data object DnsPathOk : PolicyEvent()

    data object ConnectTimeout : PolicyEvent()

    data object OutboundReady : PolicyEvent()

    data object UserDisconnect : PolicyEvent()

    data class ProbeCompleted(val aliveOutboundIds: Set<String>) : PolicyEvent()

    data object PermissionDenied : PolicyEvent()
}

sealed class PolicyCommand {
    data class StartEngine(val outboundId: String) : PolicyCommand()

    data class StopEngine(val reason: ConnectionCause) : PolicyCommand()

    data class ScheduleRetry(
        val outboundId: String,
        val delayMs: Long,
        val attempt: Int,
    ) : PolicyCommand()

    data class ProbeGroup(val outboundIds: List<String>) : PolicyCommand()

    data class ShowBanner(
        val fromOutboundId: String,
        val toOutboundId: String,
        val groupName: String,
    ) : PolicyCommand()
}
