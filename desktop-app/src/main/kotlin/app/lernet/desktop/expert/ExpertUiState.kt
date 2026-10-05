package app.lernet.desktop.expert

import androidx.compose.ui.graphics.ImageBitmap
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.policy.VerifiedInterfaceBinding
import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.PolicyControlCapabilities
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyInventory
import app.lernet.routing.policy.PolicyScope

/** Presentation snapshots are supplied by the controller; the UI never owns a network engine. */
data class ExpertUiState(
    val saved: NetworkPolicy,
    val draft: NetworkPolicy = saved,
    val profiles: List<ExpertProfile> = emptyList(),
    val folders: List<ExpertFolder> = emptyList(),
    val selectedScope: PolicyScope = PolicyScope.Device,
    val phase: ExpertPhase = ExpertPhase.STOPPED,
    val appliedRevision: Long? = null,
    val applied: NetworkPolicy? = null,
    val tunnelIdentity: String? = null,
    val capabilities: PolicyControlCapabilities = PolicyControlCapabilities.RESTART_ONLY,
    val administrator: Boolean = false,
    val reducedMotion: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val inactiveReasons: Map<String, String> = emptyMap(),
    val exits: List<ExpertExit> = emptyList(),
    val connections: List<ExpertConnection> = emptyList(),
    val events: List<ExpertEvent> = emptyList(),
    val applications: List<ExpertApplication> = emptyList(),
    val protection: ExpertProtection = ExpertProtection(),
    val externalProfiles: List<ExpertExternalProfile> = emptyList(),
    val interfaces: List<ExpertInterface> = emptyList(),
    val interfacesLoading: Boolean = false,
    val interfacesNotice: String? = null,
    val externalSavePending: String? = null,
    val externalSaveAck: String? = null,
    val externalSaveError: String? = null,
    val externalSaveErrorRequestId: String? = null,
) {
    val hasDraftChanges: Boolean get() = draft != saved
    val hasUnappliedChanges: Boolean get() = appliedRevision != saved.revision
    val inventory: PolicyInventory
        get() = PolicyInventory(
            profiles.map { it.id }.toSet(),
            folders.associate { folder ->
                folder.id to profiles.filter { it.folderId == folder.id }.map { it.id }
            }
        )
}

enum class ExpertPhase { STOPPED, STARTING, RUNNING, APPLYING, STOPPING, FAILED }

data class ExpertProfile(
    val id: String,
    val name: String,
    val folderId: String? = null,
    val protocol: String = "",
    val interfaceBinding: VerifiedInterfaceBinding? = null,
)
data class ExpertFolder(val id: String, val name: String)

data class ExpertExternalProfile(val id: String, val request: ExternalExitRequest, val fingerprint: String = "")

data class ExpertInterface(
    val binding: VerifiedInterfaceBinding,
    val label: String,
    val status: String,
    val eligible: Boolean,
    val reason: String? = null,
)
data class ExpertApplication(
    val name: String,
    val executable: String,
    val installed: Boolean,
    val running: Boolean,
    val icon: ImageBitmap? = null,
)

data class ExpertExit(
    val id: String,
    val name: String,
    val targetDescription: String,
    val phase: ExitPhase,
    val profileId: String? = null,
    val folderId: String? = null,
    val channelId: String? = null,
    val latencyMs: Long? = null,
    val activeFlows: Int = 0,
    val pendingFlows: Int = 0,
    val coldStart: Boolean = false,
    val idleRemainingMs: Long? = null,
    val reason: String? = null,
    val lastCheck: String? = null,
    val canWake: Boolean = false,
    val canSleep: Boolean = false,
)

data class ExpertConnection(
    val id: String,
    val application: String,
    val destination: String,
    val protocol: String,
    val decision: String,
    val explanation: String,
    val routeNodeIds: List<String> = emptyList(),
    val exitId: String? = null,
    val uploadedBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val active: Boolean? = null,
    val protected: Boolean = false,
    val policyRevision: Long? = null,
)

data class ExpertEvent(
    val id: String,
    val time: String,
    val title: String,
    val explanation: String,
    val warning: Boolean = false,
)

data class ExpertProtection(
    val exitFailureEnforced: Boolean = false,
    val dnsFollowsPolicy: Boolean = false,
    val ipv6Covered: Boolean = false,
    val systemGuardEnforced: Boolean = false,
    val explanation: String =
        "Системная защита от обхода TUN не подтверждена. Правила внутри работающего TUN не заменяют защиту при его остановке.",
)

sealed interface ExpertIntent {
    data class EditPolicy(val policy: NetworkPolicy) : ExpertIntent
    data class SelectScope(val scope: PolicyScope) : ExpertIntent
    data object SaveDraft : ExpertIntent
    data object DiscardDraft : ExpertIntent
    data object RestoreAppliedToDraft : ExpertIntent
    data object ApplySaved : ExpertIntent
    data object Start : ExpertIntent
    data object Stop : ExpertIntent
    data object Refresh : ExpertIntent
    data object OpenNetworkObservation : ExpertIntent
    data object Import : ExpertIntent
    data object Export : ExpertIntent
    data object ChooseExecutable : ExpertIntent
    data object EnableSystemGuard : ExpertIntent
    data object DisableSystemGuard : ExpertIntent
    data object RecoverSystemGuard : ExpertIntent
    data object RefreshInterfaces : ExpertIntent
    data class SaveExternalExit(
        val profileId: String?,
        val request: ExternalExitRequest,
        val requestId: String,
        val expectedFingerprint: String? = null,
    ) : ExpertIntent
    data class WakeExit(val id: String) : ExpertIntent
    data class SleepExit(val id: String) : ExpertIntent
}
