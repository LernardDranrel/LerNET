package app.lernet.engine.policy

import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.InactivePolicyProtection
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.routing.policy.PolicyInventory
import app.lernet.routing.policy.PolicyProgram
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A physical exit has a stable profile identity and, optionally, an isolated channel identity. */
data class ExpertExitKey(
    val profileId: String,
    val channelId: String? = null,
    val channelPath: List<String> = listOfNotNull(channelId),
    val folderId: String? = null,
)

data class ExpertTunnelAck(val identity: TunIdentity, val revision: Long)
data class ExpertRetiredCleanupFailure(
    val revision: Long,
    val reasonCode: String,
    val exitTags: List<String> = emptyList(),
)
data class ExpertProbeResult(val httpsLatencyMs: Long?, val reason: String? = null) {
    val healthy: Boolean get() = httpsLatencyMs?.let { it > 0 } == true
}

/** A failed response after a possible native commit is not evidence that the previous rules remain active. */
class ExpertStateUncertainException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Platform adapters own credentials and native handles. Acknowledgements describe actual native state. */
interface ExpertRuntimeBackend {
    val capabilities: PolicyControlCapabilities

    /** Query a host before opening its ingress; a bundled file alone does not prove live capabilities. */
    suspend fun negotiateCapabilities(): PolicyControlCapabilities = capabilities
    val events: Flow<ExpertBackendEvent> get() = emptyFlow()

    suspend fun start(program: PolicyProgram): ExpertTunnelAck
    suspend fun apply(program: PolicyProgram, expected: TunIdentity): ExpertTunnelAck
    suspend fun stop(expected: TunIdentity?)
    suspend fun wakeExit(key: ExpertExitKey, generation: Long, lifecycle: ExitLifecyclePolicy)

    /** Recreate this exit's transport after failed end-to-end checks; an ordinary wake of READY is not recovery. */
    suspend fun recoverExit(
        key: ExpertExitKey,
        generation: Long,
        lifecycle: ExitLifecyclePolicy
    ): Unit = throw UnsupportedOperationException("Обработчик не поддерживает восстановление отдельного выхода.")
    suspend fun sleepExit(key: ExpertExitKey, generation: Long)

    /** The platform must pass this budget into the actual network call, including blocking JNI. */
    suspend fun probeExit(key: ExpertExitKey, timeoutMs: Long = 30_000): ExpertProbeResult
}

sealed interface ExpertBackendEvent {
    val identity: TunIdentity? get() = null
    data class UserTraffic(val key: ExpertExitKey, val flowId: String, override val identity: TunIdentity? = null) : ExpertBackendEvent
    data class FlowClosed(val key: ExpertExitKey, val flowId: String, override val identity: TunIdentity? = null) : ExpertBackendEvent
    data class Health(
        val key: ExpertExitKey,
        val result: ExpertProbeResult,
        override val identity: TunIdentity? = null
    ) : ExpertBackendEvent
    data class TunnelLost(override val identity: TunIdentity, val reason: String) : ExpertBackendEvent

    /** Native physical-network epoch, captured with the actual tunnel acknowledgement. */
    data class NetworkChanged(
        override val identity: TunIdentity,
        val revision: Long,
        val epoch: Long,
    ) : ExpertBackendEvent
    /** Current network evidence; underlay loss does not revoke Start. */
    data class NetworkStatus(
        override val identity: TunIdentity,
        val revision: Long,
        val reason: String?,
        val recovering: Boolean = false,
    ) : ExpertBackendEvent
    data class Observation(val connection: ExpertConnectionObservation, override val identity: TunIdentity? = null) : ExpertBackendEvent
    data class DirectNetworkSnapshot(
        val facts: ExpertDirectNetworkFacts,
        override val identity: TunIdentity,
        val revision: Long,
    ) : ExpertBackendEvent

    /** Native history is bounded independently of the shared UI history. */
    data class ObservationHistory(
        val droppedCount: Long,
        val limit: Int = 500,
        override val identity: TunIdentity? = null,
        val visibleFlowIds: Set<String>? = null,
    ) : ExpertBackendEvent

    /** Native queue counts are factual; polling a native cold exit does not manufacture a Kotlin flow. */
    data class ExitStatus(
        val state: ExpertExitState,
        override val identity: TunIdentity? = null,
        val revision: Long? = null,
    ) : ExpertBackendEvent
    data class ExitsSnapshot(
        val exits: List<ExpertExitState>,
        override val identity: TunIdentity? = null,
        val revision: Long? = null,
    ) : ExpertBackendEvent
    data class FolderSelectionsSnapshot(
        val selections: Map<String, ExpertExitKey>,
        override val identity: TunIdentity? = null,
        val revision: Long? = null,
    ) : ExpertBackendEvent

    /** Failed retirement belongs to an older generation, independently of the current exit health. */
    data class RetiredCleanupSnapshot(
        val failures: List<ExpertRetiredCleanupFailure>,
        override val identity: TunIdentity,
        val revision: Long,
    ) : ExpertBackendEvent
}

data class ExpertConnectionObservation(
    val id: String,
    val application: String? = null,
    val destination: String,
    val protocol: String,
    val nodeIds: List<String> = emptyList(),
    val exit: ExpertExitKey? = null,
    val decision: String,
    val uploadedBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val active: Boolean? = null,
    val startedAtMs: Long? = null,
    val policyRevision: Long? = null,
    val sourceIp: String? = null,
    val sourcePort: Int? = null,
    val destinationIp: String? = null,
    val destinationPort: Int? = null,
    val domain: String? = null,
    val processName: String? = null,
    /** Transport and sniffed application protocol are separate facts. `protocol` retains its legacy transport meaning. */
    val network: String? = null,
    val sniffedProtocol: String? = null,
    val geoCountry: String? = null,
    val observedAtMs: Long? = null,
    val lastUpdateAtMs: Long? = null,
    val closedAtMs: Long? = null,
    val state: String? = null,
    val errorReason: String? = null,
    val errorStage: String? = null,
    val closeReason: String? = null,
    val packageNames: List<String> = emptyList(),
    val inspection: FlowInspection? = null,
    val identity: TunIdentity? = null,
)

enum class ExpertSessionPhase { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
enum class ExpertReasonLevel { INFO, WARNING, ERROR }
data class ExpertReason(val sequence: Long, val atMs: Long, val level: ExpertReasonLevel, val message: String)
data class ExpertExitState(
    val key: ExpertExitKey,
    val phase: ExitPhase,
    val latencyMs: Long? = null,
    val reason: String? = null,
    val activeFlows: Int = 0,
    val pendingFlows: Int = 0,
    val lastCheckMs: Long? = null,
)

data class ExpertRuntimeState(
    val phase: ExpertSessionPhase = ExpertSessionPhase.STOPPED,
    val desiredEnabled: Boolean = false,
    val networkReason: String? = null,
    val networkRecovering: Boolean = false,
    val capabilities: PolicyControlCapabilities = PolicyControlCapabilities.RESTART_ONLY,
    val saved: NetworkPolicy,
    val draft: NetworkPolicy = saved,
    val appliedRevision: Long? = null,
    val appliedPolicy: NetworkPolicy? = null,
    val tun: TunIdentity? = null,
    val applying: Boolean = false,
    val errors: List<String> = emptyList(),
    val draftErrors: List<String> = emptyList(),
    /** Only a successful durable workspace write clears this failure. Native ACKs are independent. */
    val draftPersistenceError: String? = null,
    val inactiveNodeIds: Set<String> = emptySet(),
    val inactiveProtections: List<InactivePolicyProtection> = emptyList(),
    val exits: List<ExpertExitState> = emptyList(),
    val selectedFolderProfiles: Map<String, String> = emptyMap(),
    val actualFolderSelections: Map<String, ExpertExitKey> = emptyMap(),
    val retiredCleanupFailures: List<ExpertRetiredCleanupFailure> = emptyList(),
    val connections: List<ExpertConnectionObservation> = emptyList(),
    val connectionHistoryLimit: Int = 500,
    val connectionHistoryTruncated: Boolean = false,
    val connectionDroppedCount: Long = 0,
    val directNetwork: ExpertDirectNetworkFacts? = null,
    val reasons: List<ExpertReason> = emptyList(),
) {
    val hasDraftChanges: Boolean get() = saved != draft
}

sealed interface ExpertIntent {
    data object Start : ExpertIntent
    data object Stop : ExpertIntent
    data class Edit(val policy: NetworkPolicy) : ExpertIntent
    data class EditChecked(val base: NetworkPolicy, val policy: NetworkPolicy) : ExpertIntent
    data class UpdateLayout(
        val scope: app.lernet.routing.policy.PolicyScope,
        val points: Map<String, app.lernet.routing.policy.PolicyCanvasPoint> = emptyMap(),
        val clear: Boolean = false,
    ) : ExpertIntent
    data object SaveDraft : ExpertIntent
    data object DiscardDraft : ExpertIntent
    data object RestoreAppliedToDraft : ExpertIntent
    data object ApplySaved : ExpertIntent
    data class InventoryChanged(val inventory: PolicyInventory) : ExpertIntent
    data class WorkspaceChanged(val saved: NetworkPolicy, val draft: NetworkPolicy, val inventory: PolicyInventory) : ExpertIntent
    data class WakeExit(val key: ExpertExitKey) : ExpertIntent
    data class SleepExit(val key: ExpertExitKey) : ExpertIntent
    data object NetworkChanged : ExpertIntent
    data object Tick : ExpertIntent
    data object ClearConnectionHistory : ExpertIntent
}

sealed interface ExpertExitResolution {
    data class Ready(val key: ExpertExitKey) : ExpertExitResolution
    data object Direct : ExpertExitResolution
    data class Blocked(val reason: String) : ExpertExitResolution
}

fun interface ExpertPolicyPersistence {
    /** Must complete durable storage before returning; failures must propagate. Drafts may be invalid. */
    fun persist(saved: NetworkPolicy, draft: NetworkPolicy)
}

data class ExpertProbeSchedule(
    val minimumIntervalMs: Long = 3_000,
    val maximumIntervalMs: Long = 7_000,
    val activeTimeoutMs: Long = 4_000,
    val candidateTimeoutMs: Long = 30_000,
    val failedChecksBeforeRecovery: Int = 2,
) {
    init {
        require(minimumIntervalMs > 0 && maximumIntervalMs >= minimumIntervalMs)
        require(activeTimeoutMs > 0 && candidateTimeoutMs > 0 && failedChecksBeforeRecovery > 0)
    }

    companion object {
        fun fromHealth(settings: PolicyHealthSettings): ExpertProbeSchedule = ExpertProbeSchedule(
            minimumIntervalMs = settings.minimumIntervalMs,
            maximumIntervalMs = settings.maximumIntervalMs,
            activeTimeoutMs = settings.activeTimeoutMs,
            failedChecksBeforeRecovery = settings.failedChecksBeforeRecovery,
        )
    }
}
