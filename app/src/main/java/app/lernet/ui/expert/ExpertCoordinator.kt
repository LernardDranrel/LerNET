package app.lernet.ui.expert

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.lernet.R
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.policy.PolicyInventoryChange
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.policy.PolicyWorkspace
import app.lernet.config.policy.PolicyWorkspaceRepository
import app.lernet.config.policy.PolicyWorkspaceStore
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.expert.ExpertAndroidEngine
import app.lernet.engine.policy.ExpertIntent
import app.lernet.engine.policy.ExpertPolicyPersistence
import app.lernet.engine.policy.ExpertRuntimeController
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.engine.redact.LerNetLog
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyDnsSettings
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.routing.policy.PolicyTarget
import app.lernet.vpn.DefaultNetworkMonitor
import app.lernet.vpn.expert.AndroidExpertEnvironment
import app.lernet.vpn.expert.AndroidExpertEnvironmentState
import app.lernet.vpn.expert.ExpertServiceRestorer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

data class ExpertUiState(
    val runtime: ExpertRuntimeState? = null,
    val inventory: TransferBundle? = null,
    val storageError: String? = null,
    val inventoryNotes: List<String> = emptyList(),
    val environment: AndroidExpertEnvironmentState? = null,
    val restoreWarning: String? = null,
)

/** Process-lifetime owner. Navigation never closes the Expert session or loses a durable draft. */
@Singleton
class ExpertCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configurations: ConfigRepository,
    private val backend: ExpertAndroidEngine,
    private val scope: CoroutineScope,
    private val environment: AndroidExpertEnvironment,
) {
    private val workspace = PolicyWorkspaceRepository(PolicyWorkspaceStore(context.filesDir.resolve("expert-workspace.json").toPath()))
    private val mutex = Mutex()

    @Volatile private var runtime: ExpertRuntimeController? = null
    private val desired = context.getSharedPreferences("expert-session", Context.MODE_PRIVATE)

    @Volatile private var restorePending = false

    @Volatile private var reconcilePending = false
    private var adoptedInventory: TransferBundle? = null
    private var automaticInventoryAllowed = true
    private val desiredEpoch = AtomicLong()
    private val mutableState = MutableStateFlow(ExpertUiState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = mutableMessages.asSharedFlow()

    init {
        ExpertServiceRestorer.register(
            isDesired = { desired.getBoolean("desired", false) },
            restore = { if (runtime == null) restorePending = true else dispatch(ExpertIntent.Start, automaticStart = true) },
            revoke = { dispatch(ExpertIntent.Stop) },
        )
        scope.launch(Dispatchers.IO) {
            combine(configurations.profiles, configurations.groups, configurations.ruleNodes) { _, _, _ -> Unit }.collect {
                mutex.withLock { refreshInventory() }
            }
        }
        scope.launch {
            DefaultNetworkMonitor.changes.drop(1).collect {
                runtime?.state?.value?.let { snapshot ->
                    app.lernet.vpn.expert.ExpertNotificationFeed.status.value =
                        app.lernet.vpn.expert.expertNotificationStatus(snapshot, DefaultNetworkMonitor.underlyingNetwork() != null)
                }
                runtime?.handle(ExpertIntent.NetworkChanged)
            }
        }
        scope.launch {
            backend.restoreWarning.collect { warning -> mutableState.update { it.copy(restoreWarning = warning) } }
        }
    }

    private suspend fun refreshInventory() {
        try {
            val controller = runtime
            if (controller != null &&
                (
                    controller.state.value.applying ||
                        controller.state.value.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
                    )
            ) {
                reconcilePending = true
                return
            }
            val bundle = TransferCodec.decode(configurations.exportTransfer())
            val previousRuntime = runtime?.state?.value
            val publishAddition = automaticInventoryAllowed &&
                PolicyInventoryChange.isAddition(adoptedInventory, bundle) &&
                previousRuntime?.phase == ExpertSessionPhase.RUNNING &&
                !previousRuntime.applying &&
                !previousRuntime.hasDraftChanges &&
                previousRuntime.appliedRevision == previousRuntime.saved.revision &&
                previousRuntime.appliedPolicy == previousRuntime.saved
            val saved = workspace.open(bundle)
            backend.updateWorkspace(saved)
            if (controller == null) {
                val created = ExpertRuntimeController(
                    initial = saved.saved,
                    initialInventory = PolicyMigration.inventory(bundle),
                    initialDraft = saved.draft,
                    platform = RoutePlatform.ANDROID,
                    backend = backend,
                    persistence = ExpertPolicyPersistence { activeSaved, draft -> workspace.persist(activeSaved, draft) },
                    scope = scope,
                    clockMs = SystemClock::elapsedRealtime,
                )
                runtime = created
                adoptedInventory = bundle
                scope.launch {
                    created.state.collect { snapshot ->
                        val before = mutableState.value
                        val inspected = if (before.environment == null || before.runtime?.phase != snapshot.phase) {
                            environment.inspect()
                        } else {
                            before.environment
                        }
                        mutableState.update { it.copy(runtime = snapshot, environment = inspected) }
                        app.lernet.vpn.expert.ExpertNotificationFeed.status.value =
                            app.lernet.vpn.expert.expertNotificationStatus(snapshot, DefaultNetworkMonitor.underlyingNetwork() != null)
                        if (snapshot.phase == ExpertSessionPhase.RUNNING && before.runtime?.phase != ExpertSessionPhase.RUNNING) {
                            // Commit handover only after ACK; a failed Expert start can still restore Simple.
                            withContext(Dispatchers.IO) {
                                runCatching { app.lernet.vpn.SimpleServiceRestorer.clearForExpert() }
                                    .onFailure { error ->
                                        if (error is CancellationException) throw error
                                        LerNetLog.e("LerNET.Restore", "Simple handover journal write failed (${error.javaClass.simpleName})")
                                        mutableState.update { it.copy(storageError = context.getString(R.string.expert_storage_error)) }
                                    }
                            }
                        }
                        if (reconcilePending &&
                            !snapshot.applying &&
                            snapshot.phase !in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
                        ) {
                            scope.launch(Dispatchers.IO) {
                                mutex.withLock {
                                    if (reconcilePending) {
                                        reconcilePending = false
                                        refreshInventory()
                                    }
                                }
                            }
                        }
                    }
                }
                if (restorePending) {
                    restorePending = false
                    dispatch(ExpertIntent.Start, automaticStart = true)
                }
            } else {
                if (controller.state.value.applying ||
                    controller.state.value.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
                ) {
                    reconcilePending = true
                } else {
                    controller.handle(ExpertIntent.WorkspaceChanged(saved.saved, saved.draft, PolicyMigration.inventory(bundle)))
                    reconcilePending = controller.state.value.saved != saved.saved || controller.state.value.draft != saved.draft
                    if (!reconcilePending) {
                        adoptedInventory = bundle
                        val current = controller.state.value
                        if (publishAddition &&
                            !current.hasDraftChanges &&
                            current.phase == ExpertSessionPhase.RUNNING &&
                            !current.applying
                        ) {
                            // Only inventory additions publish automatically; imported or unapplied user policies stay pending.
                            controller.handle(ExpertIntent.ApplySaved)
                        }
                    }
                }
            }
            val notes = workspace.lastNotes
            val inspected = environment.inspect()
            mutableState.update { it.copy(inventory = bundle, storageError = null, inventoryNotes = notes, environment = inspected) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is SerializationException) {
                LerNetLog.e("LerNET.Expert", "workspace reconciliation failed (${error.javaClass.simpleName})")
            } else {
                LerNetLog.e("LerNET.Expert", "workspace reconciliation failed", error)
            }
            mutableState.update { it.copy(storageError = context.getString(R.string.expert_storage_error)) }
        }
    }

    fun connectedVpnProfileId(): String? = backend.connectedSimpleVpnProfileId()

    fun startKeepingVpn(profileId: String, expected: NetworkPolicy) =
        dispatch(ExpertIntent.Start, automaticStart = false, handover = profileId to expected)

    fun dispatch(intent: ExpertIntent) = dispatch(intent, automaticStart = false)

    private fun dispatch(
        intent: ExpertIntent,
        automaticStart: Boolean,
        handover: Pair<String, NetworkPolicy>? = null
    ) {
        if (intent == ExpertIntent.Start || intent == ExpertIntent.Stop) backend.noteModeIntent()
        val epoch = if (intent == ExpertIntent.Start || intent == ExpertIntent.Stop) desiredEpoch.incrementAndGet() else desiredEpoch.get()
        if (intent == ExpertIntent.Stop) {
            restorePending = false
            desired.edit().putBoolean("desired", false).commit()
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (intent == ExpertIntent.Stop) {
                    runtime?.handle(intent)
                    return@launch
                }
                mutex.withLock {
                    if (intent == ExpertIntent.Start && epoch != desiredEpoch.get()) return@launch
                    if (intent == ExpertIntent.Start && handover != null) {
                        check(backend.connectedSimpleVpnProfileId() == handover.first) { "Simple VPN changed before handover" }
                        val target = PolicyTarget.Profile(
                            handover.first,
                            routeScope = PolicyMigration.effectiveScope(workspace.snapshot().legacy, handover.first)
                        )
                        if (runtime?.prepareVpnHandover(handover.second, target) != true) return@launch
                    }
                    if (intent == ExpertIntent.Start && epoch != desiredEpoch.get()) return@launch
                    if (intent == ExpertIntent.Start) {
                        require(desired.edit().putBoolean("desired", true).commit()) { "Session intent write failed" }
                        backend.authorizeStart(automaticStart)
                    }
                    backend.updateWorkspace(workspace.snapshot())
                    val controller = runtime
                    controller?.handle(intent)
                    if (intent == ExpertIntent.Start &&
                        epoch == desiredEpoch.get() &&
                        controller?.state?.value?.phase == ExpertSessionPhase.FAILED
                    ) {
                        desired.edit().putBoolean("desired", false).commit()
                    }
                    backend.updateWorkspace(workspace.snapshot())
                    val current = controller?.state?.value
                    if (intent in setOf(ExpertIntent.Start, ExpertIntent.ApplySaved) &&
                        current?.phase == ExpertSessionPhase.RUNNING &&
                        current.appliedPolicy == current.saved &&
                        current.appliedRevision == current.saved.revision
                    ) {
                        automaticInventoryAllowed = true
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LerNetLog.e("LerNET.Expert", "workspace action failed", error)
                mutableMessages.emit(context.getString(if (intent is ExpertIntent.EditChecked && error is IllegalArgumentException) {
                    R.string.expert_edit_conflict
                } else R.string.expert_storage_error))
            }
        }
    }

    fun noteSimpleModeIntent() = backend.noteModeIntent()

    suspend fun saveDraft(expected: NetworkPolicy, next: NetworkPolicy): String? = withContext(Dispatchers.IO) {
        try {
            awaitInitialized()
            mutex.withLock {
                val controller = requireNotNull(runtime)
                val current = controller.state.value
                if (current.applying || current.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)) {
                    return@withLock context.getString(R.string.expert_external_busy)
                }
                controller.handle(ExpertIntent.EditChecked(expected, next))
                val durable = workspace.snapshot()
                backend.updateWorkspace(durable)
                if (controller.state.value.draftPersistenceError != null || durable.draft != controller.state.value.draft) {
                    context.getString(R.string.expert_storage_error)
                } else null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            context.getString(R.string.expert_edit_conflict)
        } catch (error: Exception) {
            LerNetLog.e("LerNET.Expert", "draft save failed (${error.javaClass.simpleName})")
            context.getString(R.string.expert_storage_error)
        }
    }

    suspend fun saveHealthSettings(
        expected: PolicyHealthSettings,
        next: PolicyHealthSettings,
    ): String? = withContext(Dispatchers.IO) {
        try {
            awaitInitialized()
            mutex.withLock {
                val controller = requireNotNull(runtime)
                val current = controller.state.value
                if (current.applying || current.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)) {
                    return@withLock context.getString(R.string.expert_external_busy)
                }
                if (!next.isValid() || (current.draft.health != expected && current.draft.health != next)) {
                    return@withLock context.getString(R.string.expert_health_save_error)
                }
                controller.handle(ExpertIntent.Edit(current.draft.copy(health = next)))
                val durable = workspace.snapshot()
                backend.updateWorkspace(durable)
                if (durable.draft.health == next && controller.state.value.errors.isEmpty()) {
                    null
                } else {
                    context.getString(R.string.expert_health_save_error)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LerNetLog.e("LerNET.Expert", "health settings save failed (${error.javaClass.simpleName})")
            context.getString(R.string.expert_health_save_error)
        }
    }

    suspend fun saveDnsSettings(
        expected: PolicyDnsSettings,
        next: PolicyDnsSettings,
    ): String? = withContext(Dispatchers.IO) {
        try {
            awaitInitialized()
            mutex.withLock {
                val controller = requireNotNull(runtime)
                val current = controller.state.value
                if (current.applying || current.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)) {
                    return@withLock context.getString(R.string.expert_external_busy)
                }
                if (!next.isValid() || (current.draft.dns != expected && current.draft.dns != next)) {
                    return@withLock context.getString(R.string.expert_dns_save_error)
                }
                controller.handle(ExpertIntent.Edit(current.draft.copy(dns = next)))
                val durable = workspace.snapshot()
                backend.updateWorkspace(durable)
                if (durable.draft.dns == next && controller.state.value.errors.isEmpty()) {
                    null
                } else {
                    context.getString(R.string.expert_dns_save_error)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LerNetLog.e("LerNET.Expert", "DNS settings save failed (${error.javaClass.simpleName})")
            context.getString(R.string.expert_dns_save_error)
        }
    }

    suspend fun saveExternalExit(request: ExternalExitRequest, expected: TransferProfile?): String? = withContext(Dispatchers.IO) {
        try {
            awaitInitialized()
            mutex.withLock {
                val session = requireNotNull(runtime).state.value
                if (session.applying || session.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)) {
                    return@withLock context.getString(R.string.expert_external_busy)
                }
                configurations.saveExternalExit(request, expected)
                refreshInventory()
                null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Exceptions may include profile JSON; disclose only a safe failure message.
            LerNetLog.e("LerNET.Expert", "external exit save failed (${error.javaClass.simpleName})")
            context.getString(R.string.expert_external_save_error)
        }
    }

    fun export(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val raw = exportArchiveRaw()
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Document stream unavailable")
                stream.use { it.write(raw.toByteArray(Charsets.UTF_8)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LerNetLog.e("LerNET.Expert", "workspace export failed", error)
                mutableMessages.emit(context.getString(R.string.expert_storage_error))
            }
        }
    }

    /** The common archive always includes the newest committed Room inventory, plus saved rules and draft metadata. */
    suspend fun exportArchiveRaw(): String = withContext(Dispatchers.IO) {
        awaitInitialized()
        mutex.withLock {
            refreshInventory()
            check(mutableState.value.storageError == null) { context.getString(R.string.expert_storage_error) }
            check(workspace.snapshot().legacy == TransferCodec.decode(configurations.exportTransfer())) {
                context.getString(R.string.expert_export_busy)
            }
            workspace.export()
        }
    }

    private suspend fun awaitInitialized() {
        val ready = state.first { it.runtime != null && it.inventory != null || it.storageError != null }
        check(ready.storageError == null) { context.getString(R.string.expert_storage_error) }
    }

    fun refreshEnvironment(): AndroidExpertEnvironmentState = environment.inspect().also {
        val inspected = it
        mutableState.update { state -> state.copy(environment = inspected) }
    }

    fun import(uri: Uri, replace: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val raw = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        val output = StringBuilder()
                        val buffer = CharArray(8192)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read < 0) break
                            require(output.length + read <= 20_000_000) { "Workspace archive exceeds limit" }
                            output.append(buffer, 0, read)
                        }
                        output.toString()
                    } ?: error("Document stream unavailable")
                    commitImport(raw, replace)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Parser exceptions may include nearby profile credentials; record only the failure type.
                    LerNetLog.e("LerNET.Expert", "workspace import failed (${error.javaClass.simpleName})")
                    mutableMessages.emit(context.getString(R.string.expert_storage_error))
                }
            }
        }
    }

    suspend fun importArchive(raw: String): TransferBundle = withContext(Dispatchers.IO) {
        awaitInitialized()
        mutex.withLock { commitImport(raw).legacy }
    }

    private suspend fun commitImport(raw: String, replace: Boolean = false): PolicyWorkspace {
        val session = requireNotNull(runtime)
        check(
            !session.state.value.applying &&
                session.state.value.phase !in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
        )
        val plan = workspace.previewImported(raw, replace)
        automaticInventoryAllowed = false
        val adopted = workspace.commitImported(plan) { bundle ->
            // This bridge runs on an IO worker. Room itself commits one atomic transaction.
            runBlocking { configurations.restoreTransferExact(bundle) }
        }
        backend.updateWorkspace(adopted)
        session.handle(ExpertIntent.WorkspaceChanged(adopted.saved, adopted.draft, PolicyMigration.inventory(adopted.legacy)))
        adoptedInventory = adopted.legacy
        mutableState.update { it.copy(inventory = adopted.legacy, inventoryNotes = emptyList(), storageError = null) }
        return adopted
    }
}
