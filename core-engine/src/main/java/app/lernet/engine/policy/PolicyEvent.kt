package app.lernet.engine.policy

import app.lernet.engine.ConnectionCause
import app.lernet.engine.RunMode

sealed class PolicyEvent {
    data class StartRequested(
        val profileId: String,
        val outboundId: String,
        val mode: RunMode,
        val compiledJson: String,
        /** Journal reason of the start ([PolicyCommand.REASON_START], switch, resume). */
        val reason: String = PolicyCommand.REASON_START,
    ) : PolicyEvent()

    data object EngineStarted : PolicyEvent()

    data class EngineStatus(
        val uplinkBps: Long,
        val downlinkBps: Long,
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val connectionsOut: Int,
        val dnsOk: Boolean = false,
        /**
         * Replies seen on connections through the VPN exit (monotonic), or null when the
         * connection feed is unavailable. Only these may clear the failure counter.
         */
        val proxiedReplies: Long? = null,
    ) : PolicyEvent()

    data class EngineFailed(val cause: ConnectionCause) : PolicyEvent()

    data object WatchdogMiss : PolicyEvent()

    data object DnsHealthTimeout : PolicyEvent()

    data object DnsPathOk : PolicyEvent()

    data object ConnectTimeout : PolicyEvent()

    data object OutboundReady : PolicyEvent()

    /** An HTTPS request through the tunnel itself succeeded: the config really works. */
    data object TunnelVerified : PolicyEvent()

    data object UserDisconnect : PolicyEvent()

    /**
     * A round of real checks of folder configs ended. [checkedOutboundIds] are the ones that were
     * actually checked (empty = every requested one); the checked ones that did not answer rest.
     */
    data class ProbeCompleted(
        val aliveOutboundIds: Set<String>,
        val checkedOutboundIds: Set<String> = emptySet(),
    ) : PolicyEvent()

    data object PermissionDenied : PolicyEvent()

    /** The physical network changed: rounds without an answer counted on the old one say nothing now. */
    data object NetworkChanged : PolicyEvent()

    /**
     * The checked folder config could not be started (its profile disappeared meanwhile). The VPN stays:
     * the session returns to [backToOutboundId] and looks again after a pause.
     */
    data class SwitchFailed(val backToOutboundId: String) : PolicyEvent()
}

sealed class PolicyCommand {
    /** [reason] goes into the journal line ([RestartLog]): start, switch, user. */
    data class StartEngine(val outboundId: String, val reason: String = REASON_START) : PolicyCommand()

    data class StopEngine(val reason: ConnectionCause) : PolicyCommand()

    data class ScheduleRetry(
        val outboundId: String,
        val delayMs: Long,
        val attempt: Int,
        /** Journal reason: core-failed, connect-timeout, recheck, stale-core. */
        val reason: String = REASON_CORE_FAILED,
    ) : PolicyCommand()

    /** Something looked wrong but proves nothing: ask the tunnel check for one look, within its cooldown. */
    data object RequestCheck : PolicyCommand()

    /** The connection is held after a failed round: look at the tunnel again after [delayMs]. Nothing restarts. */
    data class HoldCheck(val delayMs: Long) : PolicyCommand()

    data class ProbeGroup(val outboundIds: List<String>) : PolicyCommand()

    data class ShowBanner(
        val fromOutboundId: String,
        val toOutboundId: String,
        val groupName: String,
    ) : PolicyCommand()

    companion object {
        const val REASON_START = "start"
        const val REASON_SWITCH = "switch"
        const val REASON_CORE_FAILED = "core-failed"
        const val REASON_CONNECT_TIMEOUT = "connect-timeout"
        const val REASON_RECHECK = "recheck"
        const val REASON_STALE_CORE = "stale-core"
    }
}
