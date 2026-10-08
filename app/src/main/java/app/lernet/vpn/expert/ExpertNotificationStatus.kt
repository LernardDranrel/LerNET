package app.lernet.vpn.expert

import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.engine.policy.ExpertTunnelHealth
import app.lernet.engine.policy.tunnelHealth
import kotlinx.coroutines.flow.MutableStateFlow

internal enum class ExpertNotificationStatus { STARTING, ACTIVE, OFFLINE, RECOVERING, STOPPING, FAILED, STOPPED }

internal fun expertNotificationStatus(state: ExpertRuntimeState, underlay: Boolean): ExpertNotificationStatus = when {
    state.phase == ExpertSessionPhase.FAILED -> ExpertNotificationStatus.FAILED
    state.phase == ExpertSessionPhase.STOPPED -> ExpertNotificationStatus.STOPPED
    state.phase == ExpertSessionPhase.STOPPING -> ExpertNotificationStatus.STOPPING
    state.phase == ExpertSessionPhase.STARTING -> ExpertNotificationStatus.STARTING
    !underlay -> ExpertNotificationStatus.OFFLINE
    state.tunnelHealth != ExpertTunnelHealth.HEALTHY -> ExpertNotificationStatus.RECOVERING
    else -> ExpertNotificationStatus.ACTIVE
}

/** Service observes this process-lifetime projection, without depending on Compose or ViewModels. */
internal object ExpertNotificationFeed {
    val status = MutableStateFlow(ExpertNotificationStatus.STARTING)
}
