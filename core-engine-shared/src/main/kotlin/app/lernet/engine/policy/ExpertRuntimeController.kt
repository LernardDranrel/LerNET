package app.lernet.engine.policy

import app.lernet.config.redact.SecretRedactor
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.FolderPolicy
import app.lernet.routing.policy.FolderSelection
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyExit
import app.lernet.routing.policy.PolicyInventory
import app.lernet.routing.policy.PolicyProgram
import app.lernet.routing.policy.PolicyProgramCompiler
import app.lernet.routing.policy.PolicyTarget
import app.lernet.routing.policy.ProgramRule
import app.lernet.routing.policy.UnavailableFallback
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Shared orchestration. Platform code supplies permissions, durable storage and actual native operations. */
class ExpertRuntimeController(
    initial: NetworkPolicy,
    initialInventory: PolicyInventory,
    private val platform: RoutePlatform,
    private val backend: ExpertRuntimeBackend,
    private val persistence: ExpertPolicyPersistence,
    private val scope: CoroutineScope,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    initialDraft: NetworkPolicy = initial,
    private val probeSchedule: ExpertProbeSchedule? = null,
    private val nextProbeIntervalMs: (() -> Long)? = null,
    private val maintenanceIntervalMs: Long? = 1_000,
) {
    private val mutex = Mutex()
    private val session = PolicyApplySession(initial, platform).also { it.edit(initialDraft) }
    private var inventory = initialInventory
    private var generation = 0L
    private var networkEpoch = 0L
    private var lastNativeNetworkEpoch: Long? = null
    private var reasonSequence = 0L
    private var maintenance: Job? = null
    private val lifecycles = linkedMapOf<ExpertExitKey, ExitLifecycle>()
    private val wakes = mutableMapOf<ExpertExitKey, Deferred<WakeCompletion>>()
    private val probes = mutableMapOf<ExpertExitKey, Deferred<ExpertProbeResult>>()
    private val samples = mutableMapOf<ExpertExitKey, ExitHealthSample>()
    private val failureCounts = mutableMapOf<ExpertExitKey, Int>()
    private val probeDue = mutableMapOf<ExpertExitKey, Long>()
    private val recoveryDue = mutableMapOf<ExpertExitKey, Long>()
    private val exitReasons = mutableMapOf<ExpertExitKey, String>()
    private val folderSelections = mutableMapOf<FolderKey, String>()
    private val recoveringFolders = mutableSetOf<FolderKey>()
    private val nativeExits = linkedMapOf<ExpertExitKey, ExpertExitState>()
    private var compiledDraft: PolicyProgram? = PolicyProgramCompiler.compile(session.draft, inventory, platform)
    private var compiledPolicy: NetworkPolicy? = session.draft
    private var compiledInventory: PolicyInventory? = inventory
    private var deferredWorkspace: ExpertIntent.WorkspaceChanged? = null
    private var activePolicy: NetworkPolicy? = null
    private var activeInventory: PolicyInventory? = null
    private var startingSnapshot: Pair<NetworkPolicy, PolicyInventory>? = null
    private var applyingSnapshot: Pair<NetworkPolicy, PolicyInventory>? = null
    private var startupStopUnconfirmed = false
    private var draftPersistenceError: String? = null
    private val mutableState = MutableStateFlow(
        ExpertRuntimeState(
            capabilities = backend.capabilities, saved = session.saved, draft = session.draft,
            draftErrors = compiledDraft?.errors.orEmpty().map { it.message },
            inactiveNodeIds = compiledDraft?.inactiveNodeIds.orEmpty(),
            inactiveProtections = compiledDraft?.inactiveProtections.orEmpty(),
        ),
    )
    val state: StateFlow<ExpertRuntimeState> = mutableState.asStateFlow()

    init {
        require(maintenanceIntervalMs == null || maintenanceIntervalMs > 0)
        scope.launch { backend.events.collect { event -> onBackendEvent(event) } }
    }

    fun dispatch(intent: ExpertIntent): Job = scope.launch { handle(intent) }

    suspend fun handle(intent: ExpertIntent) {
        when (intent) {
            ExpertIntent.Start -> start()
            ExpertIntent.Stop -> stop()
            is ExpertIntent.Edit -> mutex.withLock {
                session.edit(intent.policy)
                persistDraft()
                publish()
            }
            ExpertIntent.SaveDraft -> mutex.withLock {
                if (!session.hasDraftChanges) {
                    if (draftPersistenceError != null) persistDraft()
                    publish()
                    return@withLock
                }
                try {
                    val errors = session.save(inventory) { persistence.persist(it, it) }
                    if (errors.isEmpty()) draftPersistenceError = null
                    mutableState.value = mutableState.value.copy(
                        errors = errorsWithPersistence(errors), draftPersistenceError = draftPersistenceError,
                    )
                    if (errors.isEmpty()) reason(ExpertReasonLevel.INFO, "Схема сохранена. Работающие правила меняются после применения.")
                } catch (failure: Exception) {
                    reportPersistenceFailure("Не удалось сохранить схему", failure)
                }
                publish()
            }
            ExpertIntent.DiscardDraft -> mutex.withLock {
                session.discardDraft()
                persistDraft()
                publish()
            }
            ExpertIntent.RestoreAppliedToDraft -> mutex.withLock {
                val applied = activePolicy
                if (applied != null) {
                    session.edit(applied)
                    persistDraft()
                } else {
                    reason(ExpertReasonLevel.WARNING, "Работающая версия схемы не подтверждена.")
                }
                publish()
            }
            ExpertIntent.ApplySaved -> applySaved()
            is ExpertIntent.InventoryChanged -> mutex.withLock {
                inventory = intent.inventory
                val candidate = PolicyProgramCompiler.compile(session.saved, inventory, platform)
                mutableState.value = mutableState.value.copy(errors = errorsWithPersistence(candidate.errors.map { it.message }))
                publish()
            }
            is ExpertIntent.WorkspaceChanged -> mutex.withLock {
                if (session.replaceWorkspace(intent.saved, intent.draft)) {
                    inventory = intent.inventory
                    val candidate = PolicyProgramCompiler.compile(session.saved, inventory, platform)
                    mutableState.value = mutableState.value.copy(errors = errorsWithPersistence(candidate.errors.map { it.message }))
                } else {
                    if (session.state.phase == PolicyApplyPhase.APPLYING && intent.saved.revision >= session.saved.revision) {
                        deferredWorkspace = intent
                    }
                    reason(ExpertReasonLevel.WARNING, "Обновление рабочей области отложено: применяется схема либо получена старая версия.")
                }
                publish()
            }
            is ExpertIntent.WakeExit -> wakeExplicit(intent.key)
            is ExpertIntent.SleepExit -> sleepExplicit(intent.key)
            ExpertIntent.NetworkChanged -> mutex.withLock {
                invalidateNetworkHealth()
                publish()
            }
            ExpertIntent.Tick -> tick()
        }
    }

    private suspend fun start() {
        val negotiationGeneration = mutex.withLock {
            if (mutableState.value.phase !in setOf(ExpertSessionPhase.STOPPED, ExpertSessionPhase.FAILED)) return
            if (mutableState.value.tun != null || startupStopUnconfirmed) {
                reason(ExpertReasonLevel.ERROR, "Сначала остановите прежний обработчик сети.")
                return
            }
            mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.STARTING, errors = errorsWithPersistence(emptyList()))
            ++generation
        }
        val capabilities = try {
            backend.negotiateCapabilities()
        } catch (cancelled: CancellationException) {
            cancelStartup(negotiationGeneration)
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock {
                if (generation == negotiationGeneration) {
                    mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                    reportFailure("Не удалось проверить возможности ядра", failure)
                }
            }
            return
        }
        val candidate = mutex.withLock {
            if (generation != negotiationGeneration || mutableState.value.phase != ExpertSessionPhase.STARTING) return
            mutableState.value = mutableState.value.copy(capabilities = capabilities)
            if (!capabilities.preservesTun || !capabilities.atomicRules || !capabilities.independentExits) {
                mutableState.value = mutableState.value.copy(
                    phase = ExpertSessionPhase.STOPPED,
                    errors = errorsWithPersistence(listOf("Ядро не подтвердило постоянный TUN и независимые выходы.")),
                )
                reason(ExpertReasonLevel.ERROR, "Экспертный режим недоступен: перезапуск обычного VPN не сохраняет общий TUN.")
                return
            }
            val program = PolicyProgramCompiler.compile(session.saved, inventory, platform)
            if (!program.isValid) {
                mutableState.value = mutableState.value.copy(
                    phase = ExpertSessionPhase.STOPPED, errors = errorsWithPersistence(program.errors.map { it.message }),
                )
                return
            }
            mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.STARTING, errors = errorsWithPersistence(emptyList()))
            reason(ExpertReasonLevel.INFO, "Создаём общий TUN. Подтверждение работающей схемы ожидается от ядра.")
            startingSnapshot = session.saved to inventory
            negotiationGeneration to program
        }
        try {
            val ack = backend.start(candidate.second)
            val accepted = mutex.withLock {
                if (generation != candidate.first || mutableState.value.phase != ExpertSessionPhase.STARTING) {
                    false
                } else if (ack.revision != candidate.second.revision || !session.tunnelStarted(ack.identity, ack.revision)) {
                    startupStopUnconfirmed = true
                    val error = "Ядро подтвердило другую версию схемы или не сообщило идентификатор TUN."
                    val identity = ack.identity.takeIf { it.instanceId.isNotBlank() && it.interfaceId?.isBlank() != true }
                    mutableState.value = mutableState.value.copy(
                        phase = ExpertSessionPhase.FAILED, tun = identity, errors = errorsWithPersistence(listOf(error)),
                    )
                    reason(ExpertReasonLevel.ERROR, error)
                    publish()
                    false
                } else {
                    activePolicy = startingSnapshot?.first
                    activeInventory = startingSnapshot?.second
                    startingSnapshot = null
                    mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.RUNNING, tun = ack.identity)
                    reason(ExpertReasonLevel.INFO, "Общий TUN запущен. Ядро подтвердило версию ${ack.revision}.")
                    reportInactiveProtections(candidate.second)
                    publish()
                    beginMaintenance()
                    true
                }
            }
            if (!accepted) {
                backend.stop(ack.identity)
                mutex.withLock {
                    if (generation == candidate.first && startupStopUnconfirmed) {
                        startupStopUnconfirmed = false
                        startingSnapshot = null
                        mutableState.value = mutableState.value.copy(tun = null)
                        publish()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            cancelStartup(candidate.first)
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock {
                if (generation == candidate.first) {
                    if (failure is ExpertStateUncertainException) startupStopUnconfirmed = true
                    mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                    reportFailure(
                        if (startupStopUnconfirmed) "Состояние TUN не подтверждено; повторите «Остановить»" else "Общий TUN не запущен",
                        failure,
                    )
                    publish()
                }
            }
        }
    }

    private suspend fun cancelStartup(attemptGeneration: Long) = withContext(NonCancellable) {
        val owned = mutex.withLock {
            val stillStarting = mutableState.value.phase == ExpertSessionPhase.STARTING ||
                mutableState.value.phase == ExpertSessionPhase.FAILED &&
                startingSnapshot != null
            if (generation != attemptGeneration || !stillStarting) {
                false
            } else {
                startupStopUnconfirmed = true
                startingSnapshot = null
                session.tunnelStopped()
                mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.STOPPING)
                reason(ExpertReasonLevel.WARNING, "Запуск прерван. Освобождаем созданный обработчик сети.")
                publish()
                true
            }
        }
        if (!owned) return@withContext
        try {
            backend.stop(null)
            mutex.withLock {
                if (generation == attemptGeneration) {
                    clearExits()
                    mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.STOPPED, tun = null)
                    publish()
                }
            }
        } catch (failure: Exception) {
            mutex.withLock {
                if (generation == attemptGeneration) {
                    mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                    reportFailure("Остановка прерванного запуска не подтверждена; нажмите «Остановить»", failure)
                    publish()
                }
            }
        }
    }

    private suspend fun stop() {
        val operation = mutex.withLock {
            if (mutableState.value.phase == ExpertSessionPhase.STOPPED || mutableState.value.phase == ExpertSessionPhase.STOPPING) return
            val identity = mutableState.value.tun
            generation++
            maintenance?.cancel()
            maintenance = null
            wakes.values.forEach { it.cancel() }
            probes.values.forEach { it.cancel() }
            wakes.clear()
            probes.clear()
            mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.STOPPING, applying = false)
            session.tunnelStopped()
            activePolicy = null
            activeInventory = null
            publish()
            generation to identity
        }
        try {
            backend.stop(operation.second)
            mutex.withLock {
                if (generation != operation.first) return@withLock
                clearExits()
                mutableState.value = mutableState.value.copy(
                    phase = ExpertSessionPhase.STOPPED, tun = null, errors = errorsWithPersistence(emptyList()),
                )
                reason(ExpertReasonLevel.INFO, "Экспертный режим остановлен. Общий TUN освобождён.")
                publish()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation == operation.first && mutableState.value.phase == ExpertSessionPhase.STOPPING) {
                        mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                        reportFailure("Остановка прервана; состояние TUN не подтверждено, повторите «Остановить»", cancelled)
                        publish()
                    }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock {
                if (generation == operation.first) {
                    mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                    reportFailure("Не удалось подтвердить остановку TUN", failure)
                    publish()
                }
            }
        }
    }

    private suspend fun applySaved() {
        val request = mutex.withLock {
            val tun = mutableState.value.tun
            if (mutableState.value.phase != ExpertSessionPhase.RUNNING || tun == null) {
                reason(ExpertReasonLevel.WARNING, "Для применения сначала включите экспертный режим.")
                return
            }
            when (val candidate = session.requestApply(inventory, mutableState.value.capabilities, tun)) {
                is PolicyApplyRequest.Rejected -> {
                    mutableState.value = mutableState.value.copy(errors = errorsWithPersistence(candidate.reasons))
                    return
                }
                is PolicyApplyRequest.Ready -> {
                    applyingSnapshot = session.saved to inventory
                    publish()
                    Triple(generation, tun, candidate)
                }
            }
        }
        try {
            val ack = backend.apply(request.third.program, request.second)
            mutex.withLock {
                if (generation != request.first) return@withLock
                if (ack.revision != request.third.ticket.revision) {
                    session.tunnelStopped()
                    activePolicy = null
                    activeInventory = null
                    mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                    reason(ExpertReasonLevel.ERROR, "Ядро сообщило другую версию. Работающая схема не подтверждена.")
                } else {
                    session.acknowledge(request.third.ticket, ack.identity)
                    if (ack.identity != request.second) {
                        activePolicy = null
                        activeInventory = null
                        mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                    } else {
                        activePolicy = applyingSnapshot?.first
                        activeInventory = applyingSnapshot?.second
                        reportInactiveProtections(request.third.program)
                        networkEpoch++
                        probes.values.forEach { it.cancel() }
                        probes.clear()
                        wakes.values.forEach { it.cancel() }
                        wakes.clear()
                        lifecycles.values.forEach { it.invalidateStartup(clockMs()) }
                        samples.clear()
                        failureCounts.clear()
                        recoveryDue.clear()
                        folderSelections.clear()
                        recoveringFolders.clear()
                        probeDue.keys.forEach { probeDue[it] = clockMs() }
                    }
                    applyingSnapshot = null
                    mutableState.value = mutableState.value.copy(
                        errors = errorsWithPersistence(session.state.error?.let(::listOf).orEmpty()),
                    )
                    reason(
                        if (session.state.error == null) ExpertReasonLevel.INFO else ExpertReasonLevel.ERROR,
                        session.state.error ?: "Новые правила применены без замены общего TUN.",
                    )
                }
                publish()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation == request.first) {
                        session.tunnelStopped()
                        activePolicy = null
                        activeInventory = null
                        mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                        reason(ExpertReasonLevel.ERROR, "Применение прервано. Активная версия ядра не подтверждена.")
                        publish()
                    }
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            mutex.withLock {
                if (generation == request.first) {
                    if (failure is ExpertStateUncertainException) {
                        session.tunnelStopped()
                        activePolicy = null
                        activeInventory = null
                        mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED)
                        reportFailure("Ответ ядра неоднозначен; активная версия не подтверждена", failure)
                    } else {
                        session.acknowledge(request.third.ticket, request.second, safeMessage(failure))
                        reportFailure("Схема не применена; прежняя версия сохранена в ядре", failure)
                    }
                    publish()
                }
            }
        }
    }

    /** Native first-flow admission calls this before opening a physical exit. It must never bypass a Blocked result. */
    suspend fun requestExit(rule: ProgramRule, flowId: String): ExpertExitResolution =
        requestExit(rule.target, flowId, rule.protected)

    suspend fun requestExit(exit: PolicyExit, flowId: String, protected: Boolean = true): ExpertExitResolution {
        require(flowId.isNotBlank())
        val running = mutex.withLock { mutableState.value.phase == ExpertSessionPhase.RUNNING }
        if (!running) return ExpertExitResolution.Blocked("Общий TUN не работает.")
        val physicalPath = exit.physicalChannelPath
        val budgetKey = when (val target = exit.target) {
            is PolicyTarget.Profile -> ExpertExitKey(target.id, exit.channelId, physicalPath)
            is PolicyTarget.Folder -> ExpertExitKey("", exit.channelId, physicalPath, target.id)
            PolicyTarget.Direct, PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel ->
                ExpertExitKey("", exit.channelId, physicalPath)
        }
        val budget = mutex.withLock { lifecyclePolicy(budgetKey).firstFlowTimeoutMs }
        return try {
            withTimeout(budget) { resolveExit(exit, flowId, protected) }
        } catch (_: TimeoutCancellationException) {
            mutex.withLock {
                lifecycles.values.forEach { it.closeFlow(flowId, clockMs()) }
                publish()
            }
            when (val target = exit.target) {
                is PolicyTarget.Profile -> unavailable(target.fallback, protected)
                is PolicyTarget.Folder -> unavailable(target.fallback, protected)
                PolicyTarget.Direct -> if (protected) {
                    ExpertExitResolution.Blocked("Защищённая ветка не может выйти напрямую.")
                } else {
                    ExpertExitResolution.Direct
                }
                PolicyTarget.Block, PolicyTarget.CurrentExit, is PolicyTarget.Channel ->
                    ExpertExitResolution.Blocked("Время ожидания первого соединения истекло. Прямой путь не открыт.")
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    lifecycles.values.forEach { it.closeFlow(flowId, clockMs()) }
                    publish()
                }
            }
            throw cancelled
        }
    }

    private suspend fun resolveExit(
        exit: PolicyExit,
        flowId: String,
        protected: Boolean,
    ): ExpertExitResolution = when (val target = exit.target) {
        PolicyTarget.Direct -> if (protected) {
            ExpertExitResolution.Blocked("Защищённая ветка не может выйти напрямую.")
        } else {
            ExpertExitResolution.Direct
        }
        PolicyTarget.Block -> ExpertExitResolution.Blocked("Ветка запрещает выход с устройства.")
        PolicyTarget.CurrentExit, is PolicyTarget.Channel -> ExpertExitResolution.Blocked("Цель не была разрешена компилятором.")
        is PolicyTarget.Profile -> {
            val key = ExpertExitKey(target.id, exit.channelId, exit.physicalChannelPath)
            if (ensureReady(key, flowId)) ExpertExitResolution.Ready(key) else unavailable(target.fallback, protected)
        }
        is PolicyTarget.Folder -> {
            val key = resolveFolder(target.id, exit.channelId, exit.physicalChannelPath, flowId)
            if (key != null) ExpertExitResolution.Ready(key) else unavailable(target.fallback, protected)
        }
    }

    private suspend fun resolveFolder(folderId: String, channelId: String?, channelPath: List<String>, flowId: String): ExpertExitKey? {
        val recovering = mutex.withLock {
            val key = FolderKey(folderId, channelId, channelPath)
            folderSelections[key]?.takeIf { key in recoveringFolders }?.let { ExpertExitKey(it, channelId, channelPath, folderId) }
        }
        // Recover the current transport before abandoning it for a neighbor; a normal wake cannot reset a dead pool.
        recovering?.let { wakeExplicit(it) }
        val snapshot = mutex.withLock {
            val key = FolderKey(folderId, channelId, channelPath)
            val policy = (activePolicy ?: session.saved).folderPolicies.firstOrNull { it.folderId == folderId } ?: FolderPolicy(folderId)
            val members = (activeInventory ?: inventory).folderMembers[folderId].orEmpty()
            val decision = FolderExitSelection.select(
                policy, members, samples.filterKeys { it.channelPath == channelPath && it.folderId == folderId }.values.toList(), clockMs(),
                folderSelections[key], key in recoveringFolders,
            )
            Triple(key, policy, decision)
        }
        val selected = when (val decision = snapshot.third) {
            is FolderExitDecision.Selected -> decision.profileId
            is FolderExitDecision.CheckRequired -> {
                // Candidates wake because user traffic needs the folder; maintenance never wakes a sleeping exit.
                checkFolderCandidates(snapshot.second, decision.profileIds, channelId, channelPath)
                mutex.withLock {
                    val members = (activeInventory ?: inventory).folderMembers[folderId].orEmpty()
                    val refreshed = FolderExitSelection.select(
                        snapshot.second, members,
                        samples.filterKeys { it.channelPath == channelPath && it.folderId == folderId }.values.toList(), clockMs(),
                        folderSelections[snapshot.first], false,
                    )
                    (refreshed as? FolderExitDecision.Selected)?.profileId
                }
            }
            FolderExitDecision.Unavailable -> null
        }
        if (selected == null) {
            mutex.withLock { reason(ExpertReasonLevel.WARNING, "Папка $folderId: доступный выход не подтверждён.") }
            return null
        }
        val key = ExpertExitKey(selected, channelId, channelPath, folderId)
        if (!ensureReady(key, flowId)) return null
        mutex.withLock {
            if (mutableState.value.phase != ExpertSessionPhase.RUNNING) return null
            folderSelections[snapshot.first] = selected
            recoveringFolders.remove(snapshot.first)
            publish()
        }
        return key
    }

    private suspend fun checkFolderCandidates(
        policy: FolderPolicy,
        candidates: List<String>,
        channelId: String?,
        channelPath: List<String>,
    ) {
        val ordered = candidates.sortedBy { if (it == policy.preferredProfileId) 0 else 1 }
        for (group in ordered.chunked(MAX_PARALLEL_CANDIDATES)) {
            val hasHealthy = coroutineScope {
                val results = Channel<Pair<String, Boolean>>(group.size)
                val jobs = group.map { profileId ->
                    launch {
                        val key = ExpertExitKey(profileId, channelId, channelPath, policy.folderId)
                        val healthy = wakeExplicit(key) && probe(key, healthSchedule().candidateTimeoutMs).healthy
                        results.send(profileId to healthy)
                    }
                }
                var successes = 0
                try {
                    var remaining = group.size
                    while (remaining > 0) {
                        // A hung neighbor cannot consume the entire first-flow budget after a usable candidate exists.
                        val result = if (successes == 0) {
                            results.receive()
                        } else {
                            withTimeoutOrNull(CANDIDATE_SETTLE_MS) { results.receive() } ?: break
                        }
                        remaining--
                        if (result.second) {
                            successes++
                            val preferred = policy.preferredProfileId ?: ordered.firstOrNull()
                            if (policy.selection == FolderSelection.PREFERRED && result.first == preferred) break
                        }
                    }
                } finally {
                    jobs.forEach { it.cancel() }
                    results.close()
                }
                successes > 0
            }
            if (hasHealthy) return
        }
    }

    private suspend fun ensureReady(key: ExpertExitKey, flowId: String): Boolean {
        val task = mutex.withLock {
            if (key.profileId !in (activeInventory ?: inventory).profileIds ||
                mutableState.value.phase != ExpertSessionPhase.RUNNING
            ) {
                return false
            }
            val lifecycle = machine(key)
            lifecycle.resetFailed(clockMs())
            when (val admission = lifecycle.request(flowId, clockMs())) {
                FlowAdmission.Ready -> {
                    publish()
                    return true
                }
                is FlowAdmission.Wake -> launchWake(key, admission.ticket)
                is FlowAdmission.Wait -> wakes[key]
                FlowAdmission.QueueFull, FlowAdmission.Unavailable -> null
            }.also { publish() }
        } ?: return false
        val outcome = try {
            withTimeout(lifecyclePolicy(key).firstFlowTimeoutMs) { task.await() }
        } catch (_: TimeoutCancellationException) {
            mutex.withLock {
                lifecycles[key]?.closeFlow(flowId, clockMs())
                publish()
            }
            return false
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            return false
        }
        return mutex.withLock {
            mutableState.value.phase == ExpertSessionPhase.RUNNING && flowId in outcome.admittedFlowIds
        }
    }

    private suspend fun wakeExplicit(key: ExpertExitKey): Boolean {
        val task = mutex.withLock {
            if (key.profileId !in (activeInventory ?: inventory).profileIds ||
                mutableState.value.phase != ExpertSessionPhase.RUNNING
            ) {
                return false
            }
            if (nativeExits[key]?.phase == ExitPhase.READY && !needsRecovery(key)) return true
            val lifecycle = machine(key)
            if (needsRecovery(key)) lifecycle.health(false, clockMs())
            when (lifecycle.phase) {
                ExitPhase.READY -> return true
                ExitPhase.STARTING -> wakes[key]
                ExitPhase.DRAINING -> return false
                ExitPhase.DEGRADED, ExitPhase.FAILED, ExitPhase.SLEEPING -> {
                    lifecycle.resetFailed(clockMs())
                    lifecycle.warm(clockMs())?.let { launchWake(key, it) }
                }
            }.also { publish() }
        } ?: return false
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            return false
        }
        return mutex.withLock { mutableState.value.phase == ExpertSessionPhase.RUNNING && lifecycles[key]?.phase == ExitPhase.READY }
    }

    private fun launchWake(key: ExpertExitKey, ticket: WakeTicket): Deferred<WakeCompletion> {
        val hostGeneration = generation
        val wakeEpoch = networkEpoch
        val lifecyclePolicy = lifecyclePolicy(key)
        val recoveryTimeoutMs = healthSchedule().activeTimeoutMs
        val recovering = needsRecovery(key)
        val task = scope.async(start = CoroutineStart.LAZY) {
            var failure: Exception? = null
            var recoveredHealth: ExpertProbeResult? = null
            try {
                withTimeout(lifecyclePolicy.startupTimeoutMs) {
                    if (recovering) {
                        backend.recoverExit(key, ticket.generation, lifecyclePolicy)
                    } else {
                        backend.wakeExit(key, ticket.generation, lifecyclePolicy)
                    }
                    if (recovering) {
                        recoveredHealth = withTimeout(recoveryTimeoutMs) {
                            backend.probeExit(key, recoveryTimeoutMs)
                        }
                        if (recoveredHealth?.healthy != true) {
                            error(recoveredHealth?.reason ?: "Восстановленный выход не подтвердил HTTPS через туннель.")
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                failure = IllegalStateException("Время запуска выхода истекло.", timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                failure = problem
            }
            mutex.withLock {
                if (generation != hostGeneration ||
                    networkEpoch != wakeEpoch ||
                    mutableState.value.phase != ExpertSessionPhase.RUNNING
                ) {
                    return@withLock WakeCompletion()
                }
                val completed = machine(key).complete(ticket, failure == null, clockMs())
                wakes.remove(key)
                probeDue[key] = clockMs() + probeInterval()
                if (failure == null && machine(key).phase == ExitPhase.READY) {
                    nativeExits[key]?.let { nativeExits[key] = it.copy(phase = ExitPhase.READY, reason = null) }
                    recoveredHealth?.let { updateHealth(key, it) }
                    exitReasons.remove(key)
                    reason(ExpertReasonLevel.INFO, "Выход ${key.profileId} готов. Общий TUN сохранён.")
                } else {
                    exitReasons[key] = failure?.let(::safeMessage) ?: "Время запуска выхода истекло."
                    reason(ExpertReasonLevel.WARNING, "Выход ${key.profileId} не готов: ${exitReasons[key]}")
                }
                publish()
                completed
            }
        }
        wakes[key] = task
        task.start()
        return task
    }

    private suspend fun sleepExplicit(key: ExpertExitKey) {
        val ticket = mutex.withLock {
            nativeExits[key]?.let { native ->
                if (native.activeFlows > 0 ||
                    native.pendingFlows > 0 ||
                    native.phase !in setOf(ExitPhase.READY, ExitPhase.DEGRADED)
                ) {
                    reason(ExpertReasonLevel.WARNING, "Выход не засыпает: есть соединения либо он ещё запускается.")
                    return
                }
                nativeExits[key] = native.copy(phase = ExitPhase.DRAINING)
                publish()
                return@withLock WakeTicket(generation)
            }
            val result = lifecycles[key]?.requestSleep(clockMs())
            if (result == null) reason(ExpertReasonLevel.WARNING, "Выход не засыпает: есть соединения либо он ещё запускается.")
            publish()
            result
        } ?: return
        executeSleep(key, ticket)
    }

    private suspend fun executeSleep(key: ExpertExitKey, ticket: WakeTicket) {
        val hostGeneration = mutex.withLock { generation }
        var failure: Exception? = null
        try {
            backend.sleepExit(key, ticket.generation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            failure = problem
        }
        mutex.withLock {
            if (generation != hostGeneration) return@withLock
            lifecycles[key]?.stopped(ticket, clockMs(), failure == null)
            nativeExits[key]?.let {
                nativeExits[key] = it.copy(
                    phase = if (failure == null) ExitPhase.SLEEPING else ExitPhase.FAILED,
                    latencyMs = null,
                    reason = failure?.let(::safeMessage),
                )
            }
            samples.remove(key)
            probeDue.remove(key)
            probes.remove(key)?.cancel()
            if (failure != null) {
                reportFailure("Остановка выхода не подтверждена", failure)
            } else {
                reason(ExpertReasonLevel.INFO, "Выход ${key.profileId} уснул. Общий TUN продолжает работать.")
            }
            publish()
        }
    }

    private suspend fun probe(key: ExpertExitKey, timeoutMs: Long): ExpertProbeResult {
        val task = mutex.withLock {
            probes[key]?.let { return@withLock it }
            val hostGeneration = generation
            val probeEpoch = networkEpoch
            lateinit var created: Deferred<ExpertProbeResult>
            created = scope.async(start = CoroutineStart.LAZY) {
                val result = try {
                    withTimeout(timeoutMs) { backend.probeExit(key, timeoutMs) }
                } catch (_: TimeoutCancellationException) {
                    ExpertProbeResult(null, "HTTPS через выход не ответил за $timeoutMs мс.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    ExpertProbeResult(null, safeMessage(failure))
                }
                mutex.withLock {
                    if (probes[key] === created &&
                        generation == hostGeneration &&
                        networkEpoch == probeEpoch &&
                        mutableState.value.phase == ExpertSessionPhase.RUNNING
                    ) {
                        updateHealth(key, result)
                        probes.remove(key)
                        publish()
                    }
                }
                result
            }
            probes[key] = created
            created.start()
            created
        }
        return try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            ExpertProbeResult(null, "Проверка отменена при изменении сети или остановке.")
        }
    }

    suspend fun onBackendEvent(event: ExpertBackendEvent) = mutex.withLock {
        if (mutableState.value.phase != ExpertSessionPhase.RUNNING) return@withLock
        if (event.identity != null && event.identity != mutableState.value.tun) return@withLock
        val snapshotRevision = when (event) {
            is ExpertBackendEvent.ExitStatus -> event.revision
            is ExpertBackendEvent.ExitsSnapshot -> event.revision
            is ExpertBackendEvent.FolderSelectionsSnapshot -> event.revision
            is ExpertBackendEvent.NetworkChanged -> event.revision
            is ExpertBackendEvent.RetiredCleanupSnapshot -> event.revision
            is ExpertBackendEvent.UserTraffic, is ExpertBackendEvent.FlowClosed, is ExpertBackendEvent.Health,
            is ExpertBackendEvent.TunnelLost, is ExpertBackendEvent.Observation -> null
        }
        if (snapshotRevision != null && snapshotRevision != session.state.appliedRevision) return@withLock
        when (event) {
            is ExpertBackendEvent.UserTraffic -> lifecycles[event.key]?.traffic(event.flowId, clockMs())
            is ExpertBackendEvent.FlowClosed -> lifecycles[event.key]?.closeFlow(event.flowId, clockMs())
            is ExpertBackendEvent.Health -> updateHealth(event.key, event.result)
            is ExpertBackendEvent.NetworkChanged -> {
                if (event.epoch < 0 || lastNativeNetworkEpoch?.let { event.epoch <= it } == true) return@withLock
                lastNativeNetworkEpoch = event.epoch
                invalidateNetworkHealth()
            }
            is ExpertBackendEvent.TunnelLost -> {
                if (event.identity != mutableState.value.tun) return@withLock
                generation++
                session.tunnelStopped()
                maintenance?.cancel()
                clearExits()
                mutableState.value = mutableState.value.copy(phase = ExpertSessionPhase.FAILED, tun = null)
                reason(ExpertReasonLevel.ERROR, "Общий TUN потерян: ${event.reason}")
            }
            is ExpertBackendEvent.Observation -> {
                val safe = event.connection.copy(
                    application = event.connection.application?.let(SecretRedactor::redact),
                    destination = SecretRedactor.redact(event.connection.destination),
                    decision = SecretRedactor.redact(event.connection.decision),
                )
                mutableState.value = mutableState.value.copy(
                    connections = (listOf(safe) + mutableState.value.connections.filter { it.id != safe.id }).take(MAX_CONNECTIONS),
                )
            }
            is ExpertBackendEvent.ExitStatus -> {
                recordNativeExit(event.state)
            }
            is ExpertBackendEvent.ExitsSnapshot -> {
                val keys = event.exits.map { it.key }.toSet()
                nativeExits.keys.filter { it !in keys }.forEach { key ->
                    nativeExits.remove(key)
                    samples.remove(key)
                    probeDue.remove(key)
                    exitReasons.remove(key)
                    failureCounts.remove(key)
                    recoveryDue.remove(key)
                    lifecycles.remove(key)
                    wakes.remove(key)?.cancel()
                    probes.remove(key)?.cancel()
                }
                event.exits.forEach(::recordNativeExit)
            }
            is ExpertBackendEvent.FolderSelectionsSnapshot -> {
                mutableState.value = mutableState.value.copy(actualFolderSelections = event.selections.toMap())
            }
            is ExpertBackendEvent.RetiredCleanupSnapshot -> recordRetiredCleanup(event.failures)
        }
        publish()
    }

    private fun recordRetiredCleanup(failures: List<ExpertRetiredCleanupFailure>) {
        val bounded = failures.asSequence().filter { it.revision >= 0 }.take(MAX_RETIRED_FAILURES).map { failure ->
            failure.copy(
                reasonCode = ExpertNativeStatusEvidence.cleanupReasonCode(failure.reasonCode),
                exitTags = failure.exitTags.take(MAX_RETIRED_EXIT_TAGS)
                    .map { SecretRedactor.redact(it.take(128)) }.distinct().sorted(),
            )
        }.distinct().toList()
        val previous = mutableState.value.retiredCleanupFailures.toSet()
        bounded.filter { it !in previous }.forEach { failure ->
            val detail = when (failure.reasonCode) {
                "exit_stop_failed" -> "не подтверждена остановка прежнего выхода"
                "generation_stop_failed" -> "не подтверждено освобождение прежнего поколения правил"
                else -> "освобождение прежних ресурсов ещё не подтверждено"
            }
            reason(
                ExpertReasonLevel.ERROR,
                "Предыдущая версия правил ${failure.revision}: $detail. " +
                    "Текущие выходы проверяются отдельно. Повторите остановку экспертного режима для освобождения ресурсов.",
            )
        }
        mutableState.value = mutableState.value.copy(retiredCleanupFailures = bounded)
    }

    /** Invalidate estimates without treating a physical network change as proof of a dead exit. */
    private fun invalidateNetworkHealth() {
        networkEpoch++
        probes.values.forEach { it.cancel() }
        probes.clear()
        wakes.values.forEach { it.cancel() }
        wakes.clear()
        lifecycles.values.forEach { it.invalidateStartup(clockMs()) }
        samples.clear()
        failureCounts.clear()
        recoveryDue.clear()
        nativeExits.replaceAll { _, state -> state.copy(latencyMs = null, lastCheckMs = null) }
        probeDue.keys.forEach { probeDue[it] = clockMs() }
        recoveringFolders.addAll(folderSelections.keys)
        reason(ExpertReasonLevel.WARNING, "Сеть изменилась. Старые оценки выходов сброшены, активные пути проверяются заново.")
    }

    private fun recordNativeExit(native: ExpertExitState) {
        val degraded = native.phase == ExitPhase.READY &&
            failureCounts.getOrDefault(native.key, 0) >= healthSchedule().failedChecksBeforeRecovery
        nativeExits[native.key] = native.copy(
            phase = if (degraded) ExitPhase.DEGRADED else native.phase,
            reason = if (degraded) exitReasons[native.key] else native.reason?.let(SecretRedactor::redact),
            latencyMs = if (degraded) samples[native.key]?.httpsLatencyMs else native.latencyMs,
            lastCheckMs = if (degraded && !mutableState.value.capabilities.nativeHealthRecovery) {
                samples[native.key]?.observedAtMs
            } else {
                native.lastCheckMs
            },
        )
        if (native.phase in setOf(ExitPhase.READY, ExitPhase.DEGRADED)) {
            if (native.key !in probeDue) probeDue[native.key] = clockMs() + probeInterval()
        } else {
            probeDue.remove(native.key)
            samples.remove(native.key)
            failureCounts.remove(native.key)
            recoveryDue.remove(native.key)
            probes.remove(native.key)?.cancel()
        }
    }

    private fun updateHealth(key: ExpertExitKey, result: ExpertProbeResult) {
        val phase = nativeExits[key]?.phase ?: lifecycles[key]?.phase
        if (phase != null && phase !in setOf(ExitPhase.READY, ExitPhase.DEGRADED)) return
        val now = clockMs()
        val recoveryThreshold = healthSchedule().failedChecksBeforeRecovery
        val failed = if (result.healthy) 0 else failureCounts.getOrDefault(key, 0) + 1
        failureCounts[key] = failed
        val cooldown = (activePolicy ?: session.saved).folderPolicies.filter {
            key.profileId in (activeInventory ?: inventory).folderMembers[it.folderId].orEmpty()
        }
            .maxOfOrNull { it.cooldownMs } ?: DEFAULT_COOLDOWN_MS
        if (result.healthy || failed >= recoveryThreshold || key !in samples) {
            samples[key] = ExitHealthSample(
                key.profileId, now, result.httpsLatencyMs,
                if (failed >= recoveryThreshold) now + cooldown else 0,
            )
        }
        lifecycles[key]?.health(result.healthy || failed < recoveryThreshold, now)
        nativeExits[key]?.let { status ->
            nativeExits[key] = status.copy(
                phase = if (result.healthy && status.phase == ExitPhase.DEGRADED) {
                    ExitPhase.READY
                } else if (failed >= recoveryThreshold) {
                    ExitPhase.DEGRADED
                } else {
                    status.phase
                },
                latencyMs = result.httpsLatencyMs,
                lastCheckMs = if (mutableState.value.capabilities.nativeHealthRecovery) status.lastCheckMs else now,
                reason = result.reason?.let(SecretRedactor::redact),
            )
        }
        probeDue[key] = now + probeInterval()
        if (result.healthy) {
            exitReasons.remove(key)
            recoveryDue.remove(key)
            folderSelections.filterValues { it == key.profileId }.keys.filter {
                it.channelPath == key.channelPath && it.folderId == key.folderId
            }.forEach(recoveringFolders::remove)
        } else {
            exitReasons[key] = result.reason?.let(SecretRedactor::redact) ?: "HTTPS через выход не получил ответ."
        }
        if (failed == recoveryThreshold) {
            folderSelections.filterValues { it == key.profileId }.keys.filter {
                it.channelPath == key.channelPath && it.folderId == key.folderId
            }
                .forEach(recoveringFolders::add)
            reason(
                ExpertReasonLevel.WARNING,
                "Выход ${key.profileId}: $failed проверки подряд не прошли. Следующий поток получит восстановление.",
            )
        }
        if (!mutableState.value.capabilities.nativeHealthRecovery &&
            failed >= recoveryThreshold &&
            now >= recoveryDue.getOrDefault(key, 0)
        ) {
            recoveryDue[key] = now + cooldown
            scope.launch { wakeExplicit(key) }
        }
    }

    private fun needsRecovery(key: ExpertExitKey): Boolean = nativeExits[key]?.phase == ExitPhase.DEGRADED ||
        failureCounts.getOrDefault(key, 0) >= healthSchedule().failedChecksBeforeRecovery

    private suspend fun tick() {
        val work = mutex.withLock {
            if (mutableState.value.phase != ExpertSessionPhase.RUNNING) return
            val now = clockMs()
            val stops = mutableListOf<Pair<ExpertExitKey, WakeTicket>>()
            lifecycles.filterKeys { it !in nativeExits }.forEach { (key, lifecycle) ->
                val result = lifecycle.tick(now)
                result.cancelWakeTicket?.let { wakes.remove(key)?.cancel() }
                result.stopTicket?.let { stops += key to it }
            }
            val checks = if (mutableState.value.capabilities.nativeHealthRecovery) {
                emptyList()
            } else {
                probeDue.filter { (key, due) ->
                    val phase = nativeExits[key]?.phase ?: lifecycles[key]?.phase
                    due <= now && phase in setOf(ExitPhase.READY, ExitPhase.DEGRADED) && key !in probes
                }.keys.toList()
            }
            publish()
            stops to checks
        }
        work.first.forEach { (key, ticket) -> scope.launch { executeSleep(key, ticket) } }
        work.second.forEach { key -> scope.launch { probe(key, healthSchedule().activeTimeoutMs) } }
    }

    private fun beginMaintenance() {
        val interval = maintenanceIntervalMs ?: return
        maintenance?.cancel()
        maintenance = scope.launch {
            while (true) {
                delay(interval)
                tick()
            }
        }
    }

    private fun lifecyclePolicy(key: ExpertExitKey): ExitLifecyclePolicy {
        val policy = activePolicy ?: session.saved
        return policy.channels.firstOrNull { it.id == key.channelId }?.lifecycle
            ?: policy.folderPolicies.firstOrNull { it.folderId == key.folderId }?.lifecycle
            ?: policy.profilePolicies.firstOrNull { it.profileId == key.profileId }?.lifecycle
            ?: ExitLifecyclePolicy()
    }

    private fun machine(key: ExpertExitKey): ExitLifecycle = lifecycles.getOrPut(key) { ExitLifecycle(lifecyclePolicy(key), clockMs()) }
    private fun healthSchedule(): ExpertProbeSchedule = probeSchedule ?: ExpertProbeSchedule.fromHealth(
        (activePolicy ?: startingSnapshot?.first ?: session.saved).health,
    )

    private fun probeInterval(): Long {
        val schedule = healthSchedule()
        val interval = nextProbeIntervalMs?.invoke()
            ?: Random.nextLong(schedule.minimumIntervalMs, schedule.maximumIntervalMs + 1)
        return interval.coerceIn(schedule.minimumIntervalMs, schedule.maximumIntervalMs)
    }
    private fun unavailable(fallback: UnavailableFallback, protected: Boolean): ExpertExitResolution = if (protected) {
        ExpertExitResolution.Blocked("Защищённая ветка закрыта: доступного туннеля нет, прямой путь запрещён.")
    } else {
        when (fallback) {
            UnavailableFallback.BLOCK -> ExpertExitResolution.Blocked("Доступного выхода нет. Ветка закрыта, прямой путь запрещён.")
            UnavailableFallback.DIRECT -> ExpertExitResolution.Direct
        }
    }

    private fun persistDraft() {
        try {
            persistence.persist(session.saved, session.draft)
            draftPersistenceError = null
            mutableState.value = mutableState.value.copy(errors = emptyList(), draftPersistenceError = null)
        } catch (failure: Exception) {
            reportPersistenceFailure("Черновик в памяти; сохранение на диск не удалось", failure)
        }
    }

    private fun errorsWithPersistence(errors: List<String>): List<String> =
        (errors + listOfNotNull(draftPersistenceError)).distinct()

    private fun reportPersistenceFailure(prefix: String, failure: Exception) {
        val message = "$prefix: ${safeMessage(failure)}"
        draftPersistenceError = message
        mutableState.value = mutableState.value.copy(errors = listOf(message), draftPersistenceError = message)
        reason(ExpertReasonLevel.ERROR, message)
    }

    private fun publish() {
        if (session.state.phase != PolicyApplyPhase.APPLYING) {
            deferredWorkspace?.let { pending ->
                deferredWorkspace = null
                if (session.replaceWorkspace(pending.saved, pending.draft)) inventory = pending.inventory
            }
        }
        if (compiledPolicy !== session.draft || compiledInventory !== inventory) {
            compiledDraft = PolicyProgramCompiler.compile(session.draft, inventory, platform)
            compiledPolicy = session.draft
            compiledInventory = inventory
        }
        val program = requireNotNull(compiledDraft)
        mutableState.value = mutableState.value.copy(
            saved = session.saved,
            draft = session.draft,
            appliedRevision = session.state.appliedRevision,
            appliedPolicy = activePolicy,
            applying = session.state.phase == PolicyApplyPhase.APPLYING,
            draftPersistenceError = draftPersistenceError,
            errors = errorsWithPersistence(mutableState.value.errors),
            inactiveNodeIds = program.inactiveNodeIds,
            inactiveProtections = program.inactiveProtections,
            draftErrors = program.errors.map { it.message },
            exits = lifecycles.filterKeys { it !in nativeExits }.map { (key, machine) ->
                val snapshot = machine.snapshot()
                ExpertExitState(
                    key, snapshot.phase, samples[key]?.httpsLatencyMs, exitReasons[key], snapshot.activeFlows,
                    snapshot.pendingFlows, samples[key]?.observedAtMs,
                )
            } + nativeExits.values,
            selectedFolderProfiles = folderSelections.entries.associate { (key, profileId) -> key.displayKey() to profileId },
        )
    }

    private fun clearExits() {
        wakes.values.forEach { it.cancel() }
        probes.values.forEach { it.cancel() }
        wakes.clear()
        probes.clear()
        lifecycles.clear()
        samples.clear()
        failureCounts.clear()
        probeDue.clear()
        recoveryDue.clear()
        exitReasons.clear()
        folderSelections.clear()
        recoveringFolders.clear()
        nativeExits.clear()
        lastNativeNetworkEpoch = null
        mutableState.value = mutableState.value.copy(actualFolderSelections = emptyMap(), retiredCleanupFailures = emptyList())
        activePolicy = null
        activeInventory = null
        startingSnapshot = null
        applyingSnapshot = null
        startupStopUnconfirmed = false
    }

    private fun reportFailure(prefix: String, failure: Exception) {
        val message = "$prefix: ${safeMessage(failure)}"
        mutableState.value = mutableState.value.copy(errors = errorsWithPersistence(listOf(message)))
        reason(ExpertReasonLevel.ERROR, message)
    }

    private fun reportInactiveProtections(program: PolicyProgram) {
        if (program.inactiveProtections.isNotEmpty()) {
            reason(
                ExpertReasonLevel.WARNING,
                "Защищённых веток для другой платформы: ${program.inactiveProtections.size}. " +
                    "Здесь они не участвуют в маршрутизации; их защита на этом устройстве не действует.",
            )
        }
    }

    private fun safeMessage(failure: Exception): String {
        val message = failure.message ?: failure.javaClass.simpleName
        return knownExpertNativeFailureExplanation(message) ?: SecretRedactor.redact(message)
    }
    private fun reason(level: ExpertReasonLevel, message: String) {
        val entry = ExpertReason(++reasonSequence, clockMs(), level, SecretRedactor.redact(message))
        mutableState.value = mutableState.value.copy(reasons = (mutableState.value.reasons + entry).takeLast(MAX_REASONS))
    }

    private data class FolderKey(val folderId: String, val channelId: String?, val channelPath: List<String>) {
        fun displayKey(): String = if (channelPath.isEmpty()) folderId else (listOf(folderId) + channelPath).joinToString("/")
    }

    companion object {
        private const val MAX_REASONS = 200
        private const val MAX_CONNECTIONS = 500
        private const val MAX_RETIRED_FAILURES = 100
        private const val MAX_RETIRED_EXIT_TAGS = 32
        private const val MAX_PARALLEL_CANDIDATES = 4
        private const val CANDIDATE_SETTLE_MS = 500L
        private const val DEFAULT_COOLDOWN_MS = 30_000L
    }
}
