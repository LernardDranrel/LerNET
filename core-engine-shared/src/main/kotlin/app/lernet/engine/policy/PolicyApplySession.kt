package app.lernet.engine.policy

import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyInventory
import app.lernet.routing.policy.PolicyProgram
import app.lernet.routing.policy.PolicyProgramCompiler

data class PolicyControlCapabilities(
    val preservesTun: Boolean,
    val atomicRules: Boolean,
    val independentExits: Boolean,
    val nativeHealthRecovery: Boolean = false,
) {
    companion object {
        /** Conservative contract of the bundled 1.14.1-lx.8 run/startOrReloadService paths. */
        val RESTART_ONLY = PolicyControlCapabilities(false, false, false)
    }
}

data class TunIdentity(val instanceId: String, val interfaceId: String? = null)
data class PolicyApplyTicket(val generation: Long, val revision: Long)

sealed interface PolicyApplyRequest {
    data class Ready(val ticket: PolicyApplyTicket, val program: PolicyProgram) : PolicyApplyRequest
    data class Rejected(val reasons: List<String>) : PolicyApplyRequest
}

enum class PolicyApplyPhase { IDLE, APPLYING, FAILED }

data class PolicyApplyState(
    val savedRevision: Long,
    val appliedRevision: Long? = null,
    val phase: PolicyApplyPhase = PolicyApplyPhase.IDLE,
    val error: String? = null,
)

/** Publication requires an acknowledgement from an atomic backend and the original TUN identity. */
class PolicyApplySession(initial: NetworkPolicy, private val platform: RoutePlatform) {
    @Volatile var saved: NetworkPolicy = initial
        private set

    @Volatile var draft: NetworkPolicy = initial
        private set

    @Volatile var state = PolicyApplyState(initial.revision)
        private set
    private var generation = 0L
    private var pending: PolicyApplyTicket? = null
    private var pendingTun: TunIdentity? = null
    private var activeTun: TunIdentity? = null

    @Synchronized
    fun edit(policy: NetworkPolicy) {
        draft = policy.copy(revision = saved.revision)
    }

    @Synchronized
    fun discardDraft() {
        draft = saved
    }

    /** The platform persists the returned value before presenting it as saved. */
    @Synchronized
    fun save(inventory: PolicyInventory, persist: (NetworkPolicy) -> Unit): List<String> {
        if (pending != null) return listOf("Дождитесь применения предыдущей версии")
        val candidate = draft.copy(revision = Math.addExact(saved.revision, 1))
        val program = PolicyProgramCompiler.compile(candidate, inventory, platform)
        if (!program.isValid) return program.errors.map { it.message }
        persist(candidate)
        saved = candidate
        draft = candidate
        state = state.copy(savedRevision = candidate.revision)
        return emptyList()
    }

    @Synchronized
    fun requestApply(inventory: PolicyInventory, capabilities: PolicyControlCapabilities, tun: TunIdentity): PolicyApplyRequest {
        if (pending != null) return PolicyApplyRequest.Rejected(listOf("Предыдущее применение ещё не завершено"))
        if (tun.instanceId.isBlank()) return PolicyApplyRequest.Rejected(listOf("TUN не подтверждён"))
        if (!capabilities.preservesTun || !capabilities.atomicRules || !capabilities.independentExits) {
            return PolicyApplyRequest.Rejected(listOf("Этот обработчик ещё не поддерживает атомарное применение без остановки TUN"))
        }
        val program = PolicyProgramCompiler.compile(saved, inventory, platform)
        if (!program.isValid) return PolicyApplyRequest.Rejected(program.errors.map { it.message })
        if (activeTun != null && activeTun != tun) state = state.copy(appliedRevision = null)
        val ticket = PolicyApplyTicket(++generation, saved.revision)
        pending = ticket
        pendingTun = tun
        state = state.copy(phase = PolicyApplyPhase.APPLYING, error = null)
        return PolicyApplyRequest.Ready(ticket, program)
    }

    /** False means a stale callback. The caller must not publish its candidate as current. */
    @Synchronized
    fun acknowledge(ticket: PolicyApplyTicket, tun: TunIdentity, failure: String? = null): Boolean {
        if (ticket != pending) return false
        val changedTun = tun != pendingTun
        pending = null
        pendingTun = null
        if (changedTun) {
            activeTun = null
            state = state.copy(
                appliedRevision = null, phase = PolicyApplyPhase.FAILED,
                error = "TUN изменился во время применения. Состояние маршрутизации требует проверки."
            )
        } else if (failure != null) {
            // Backend contract: a failed atomic transaction retains the previous applied rules.
            state = state.copy(phase = PolicyApplyPhase.FAILED, error = failure)
        } else {
            activeTun = tun
            state = state.copy(appliedRevision = ticket.revision, phase = PolicyApplyPhase.IDLE, error = null)
        }
        return true
    }

    @Synchronized
    fun tunnelStopped() {
        generation++
        pending = null
        pendingTun = null
        activeTun = null
        state = state.copy(appliedRevision = null, phase = PolicyApplyPhase.IDLE, error = null)
    }

    /** Starting a stopped host is distinct from hot application. Only a native acknowledgement may call this. */
    @Synchronized
    fun tunnelStarted(tun: TunIdentity, revision: Long): Boolean {
        if (tun.instanceId.isBlank() ||
            tun.interfaceId?.isBlank() == true ||
            revision !in 0..saved.revision ||
            pending != null
        ) {
            return false
        }
        activeTun = tun
        state = state.copy(appliedRevision = revision, phase = PolicyApplyPhase.IDLE, error = null)
        return true
    }

    /** Repository refresh may replace saved data, never the last confirmed native revision. */
    @Synchronized
    fun replaceWorkspace(replacement: NetworkPolicy, replacementDraft: NetworkPolicy): Boolean {
        if (pending != null || replacement.revision < saved.revision) return false
        if (replacement.revision == saved.revision && replacement != saved) return false
        saved = replacement
        draft = replacementDraft.copy(revision = replacement.revision)
        state = state.copy(savedRevision = replacement.revision)
        return true
    }

    val hasDraftChanges: Boolean get() = draft != saved
}
