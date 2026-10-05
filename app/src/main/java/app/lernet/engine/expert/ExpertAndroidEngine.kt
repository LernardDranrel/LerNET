package app.lernet.engine.expert

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import app.lernet.BuildConfig
import app.lernet.R
import app.lernet.config.policy.PolicyWorkspace
import app.lernet.config.redact.SecretRedactor
import app.lernet.engine.ConnectionController
import app.lernet.engine.LibboxBoxEngine
import app.lernet.engine.SimpleModeRestorePoint
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.EnginePlatform
import app.lernet.engine.policy.ExpertBackendEvent
import app.lernet.engine.policy.ExpertConnectionObservation
import app.lernet.engine.policy.ExpertExitKey
import app.lernet.engine.policy.ExpertExitState
import app.lernet.engine.policy.ExpertProbeResult
import app.lernet.engine.policy.ExpertRuntimeBackend
import app.lernet.engine.policy.ExpertStateUncertainException
import app.lernet.engine.policy.ExpertTunnelAck
import app.lernet.engine.policy.PolicyConfigAssembler
import app.lernet.engine.policy.PolicyControlCapabilities
import app.lernet.engine.policy.TunIdentity
import app.lernet.engine.policy.expertNativeFailureExplanation
import app.lernet.engine.redact.LerNetLog
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.PolicyProgram
import app.lernet.settings.SettingsStore
import app.lernet.vpn.DefaultNetworkMonitor
import app.lernet.vpn.LerNetVpnService
import app.lernet.vpn.LibboxNative
import app.lernet.vpn.LibboxPlatformRegistry
import app.lernet.vpn.VpnRuntime
import app.lernet.vpn.expert.AndroidExpertEnvironment
import app.lernet.vpn.expert.ExpertVpnNotification
import app.lernet.vpn.expert.ExpertVpnSession
import app.lernet.vpn.expert.ExpertVpnToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Android owns one VpnService; native generations only replace policy and independent exit resources. */
@Singleton
class ExpertAndroidEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val simpleEngine: LibboxBoxEngine,
    private val simpleController: ConnectionController,
    private val environment: AndroidExpertEnvironment,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : ExpertRuntimeBackend {
    private val mutex = Mutex()
    private val bridge = ExpertNativeBridge()
    private val workspace = AtomicReference<PolicyWorkspace?>(null)
    private val automaticStart = AtomicBoolean(false)
    private val restartJournal = ExpertRestartJournal(ExpertRestartFileStorage(context.filesDir.resolve("expert-restart.json")))
    private val mutableRestoreWarning = MutableStateFlow<String?>(null)
    val restoreWarning = mutableRestoreWarning.asStateFlow()
    private val mutableEvents = MutableSharedFlow<ExpertBackendEvent>(extraBufferCapacity = 256)
    private var token: ExpertVpnToken? = null

    @Volatile
    private var native: ExpertNativeSession? = null
    private var nativeAck: ExpertNativeAck? = null

    @Volatile
    private var appliedSnapshot: NativeAppliedSnapshot? = null

    @Volatile
    private var tun: TunIdentity? = null
    private var serviceTun: TunIdentity? = null
    private var polling: Job? = null
    private var exitTags: Map<ExpertExitKey, String> = emptyMap()
    private val tagBindings = mutableMapOf<String, ExpertExitKey>()
    private var observedNetworkEpoch: Long? = null
    private val trackedFlows = mutableMapOf<String, ExpertExitKey>()
    private val exitGenerations = mutableMapOf<ExpertExitKey, Long>()
    private val lossReported = AtomicBoolean(false)
    private val operationEpoch = AtomicLong(0)
    private val modeIntentEpoch = AtomicLong(0)

    @Volatile
    private var stopping = false

    @Volatile
    private var serviceFailure: String? = null

    override val capabilities: PolicyControlCapabilities get() = bridge.capabilities
    override val events: Flow<ExpertBackendEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch(Dispatchers.IO) {
            DefaultNetworkMonitor.changes.drop(1).collect {
                val snapshot = appliedSnapshot ?: return@collect
                if (stopping || serviceFailure != null) return@collect
                try {
                    // IP/DNS changes can keep the same Android interface index. Notify native
                    // independently of its interface-name listener so cached exit health expires.
                    snapshot.session.networkChanged()
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if (appliedSnapshot === snapshot && !stopping) {
                        LerNetLog.w(TAG, "Expert underlay reset failed", failure)
                        signalLost("underlay_reset_failed")
                    }
                }
            }
        }
    }

    fun updateWorkspace(snapshot: PolicyWorkspace) {
        workspace.set(snapshot)
    }

    /** The coordinator distinguishes a system restart from an explicit user authorization. */
    fun authorizeStart(automatic: Boolean) {
        automaticStart.set(automatic)
    }

    /** Called synchronously at a UI mode/connection intent, before coroutine dispatch. */
    fun noteModeIntent() {
        modeIntentEpoch.incrementAndGet()
        operationEpoch.incrementAndGet()
    }

    override suspend fun start(program: PolicyProgram): ExpertTunnelAck = mutex.withLock {
        check(native == null && token == null) { "Expert session is already running" }
        val restoring = automaticStart.getAndSet(false)
        val epoch = operationEpoch.incrementAndGet()
        val modeEpoch = modeIntentEpoch.get()
        serviceFailure = null
        stopping = false
        lossReported.set(false)
        val supported = capabilities
        check(supported.preservesTun && supported.atomicRules && supported.independentExits) {
            context.getString(R.string.expert_platform_native_missing)
        }
        check(VpnService.prepare(context) == null) { context.getString(R.string.expert_platform_missing_permission) }
        check(!environment.inspect().anotherVpnVisible) { context.getString(R.string.expert_platform_other_vpn) }
        val source = workspace.get() ?: error("Expert workspace has not been initialized")
        require(source.saved.revision == program.revision) { "Expert workspace revision changed before start" }
        require(AndroidProtectedRuleGate.supports(program, Build.VERSION.SDK_INT)) {
            context.getString(R.string.expert_platform_package_protection_unavailable)
        }
        val defaults = settings.settings.first().engineDefaults
        val assembled = withContext(Dispatchers.Default) {
            PolicyConfigAssembler.assemble(
                source, program, EnginePlatform.ANDROID, defaults = defaults, ruleSetDirectory = simpleController.ruleSetDirectoryPath,
                probeUrl = ConfigAssembler.ANDROID_PROBE_URL,
            )
        }
        require(assembled.isValid) { assembled.errors.joinToString("; ") }
        val fingerprint = restartFingerprint(source, defaults, assembled)
        if (restoring && !withContext(Dispatchers.IO) { restartJournal.matches(fingerprint) }) {
            val explanation = context.getString(R.string.expert_platform_restart_blocked)
            mutableRestoreWarning.value = explanation
            throw IllegalStateException(explanation)
        }
        check(operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch) {
            "Expert start was cancelled during policy preparation"
        }
        invalidateRestartEligibility()
        mutableRestoreWarning.value = null
        var previousSimple: SimpleModeRestorePoint? = null
        try {
            previousSimple = simpleController.suspendForModeSwitch {
                operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch
            }
            check(operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch) {
                "Expert start was cancelled before service reservation"
            }
            // Capture the acquired lease even if the caller is cancelled as the draining
            // coroutine returns; withContext otherwise discards its result on cancellation.
            withContext(NonCancellable) {
                token = simpleEngine.reserveExpert(
                    stillDesired = { operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch },
                ) { _, reason ->
                    serviceFailure = reason
                    scope.launch { signalLost(reason) }
                }
            }
            check(operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch) {
                "Expert start was cancelled before foreground service activation"
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, LerNetVpnService::class.java).setAction(ExpertVpnSession.ACTION_START)
                    .putExtra(ExpertVpnSession.EXTRA_GENERATION, token!!.generation),
            )
            val platform = LibboxPlatformRegistry.await()
            check(VpnRuntime.current() != null && ExpertVpnSession.isOwned) { "Expert VPN service did not start" }
            val root = File(context.filesDir, "libbox")
            val working = File(root, "working").apply { mkdirs() }
            val temporary = File(root, "temp").apply { mkdirs() }
            withContext(Dispatchers.IO) {
                LibboxNative.bootstrap(context, root.absolutePath, working.absolutePath, temporary.absolutePath)
                check(operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch) {
                    "Expert start was cancelled before native preparation"
                }
                val session = bridge.create(assembled.ingressJson, platform).also { native = it }
                check(operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch) {
                    "Expert start was cancelled before TUN activation"
                }
                val actual = session.start(program.revision, assembled.policyJson, assembled.exitManifestJson)
                check(operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch) {
                    "Expert start finished after stop was requested"
                }
                check(actual.revision == program.revision) { "Native Expert start acknowledged a different revision" }
                check(serviceFailure == null) { "Expert VPN service was stopped during start" }
                val owned = ExpertVpnSession.identity ?: error("Android did not establish an owned Expert TUN")
                serviceTun = owned
                nativeAck = actual
                tun = identity(actual, owned)
                exitTags = assembled.exitTags
                tagBindings.putAll(exitTags.entries.associate { it.value to it.key })
                appliedSnapshot = NativeAppliedSnapshot(session, actual, tun!!, owned, assembled.exitTags)
                promoteRestartEligibility(fingerprint, actual.revision)
                (VpnRuntime.current() as? LerNetVpnService)?.let { service ->
                    runCatching {
                        service.getSystemService(NotificationManager::class.java).notify(
                            17, ExpertVpnNotification.build(service, "lernet.vpn", active = true),
                        )
                    }.onFailure { failure ->
                        LerNetLog.w(TAG, "Expert notification keeps initial foreground content", failure)
                    }
                }
                startPolling()
                ExpertTunnelAck(tun!!, actual.revision)
            }
        } catch (failure: Throwable) {
            var cleanupConfirmed = false
            try {
                if (token != null || native != null) withContext(NonCancellable) { cleanup() }
                cleanupConfirmed = true
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            var restored = false
            var rollbackAttempted = false
            val previous = previousSimple
            val mayRollback = failure !is CancellationException || failure is TimeoutCancellationException
            if (mayRollback &&
                previous != null &&
                cleanupConfirmed &&
                operationEpoch.get() == epoch &&
                modeIntentEpoch.get() == modeEpoch
            ) {
                rollbackAttempted = true
                try {
                    restored = withContext(NonCancellable) {
                        simpleController.restoreAfterModeSwitch(previous, {
                            operationEpoch.get() == epoch && modeIntentEpoch.get() == modeEpoch && !ExpertVpnSession.isOwned
                        })
                    }
                } catch (restoreFailure: Exception) {
                    if (restoreFailure is CancellationException) throw restoreFailure
                    failure.addSuppressed(restoreFailure)
                }
            }
            LerNetLog.e(TAG, "Expert start failed", failure)
            // A startup budget timeout also cancels its caller. Cleanup and bounded rollback
            // must finish first; a real newer Stop/mode intent is fenced by the epochs above.
            if (failure is CancellationException) throw failure
            val message = when {
                restored -> R.string.expert_platform_start_failed_restored
                previous != null && !cleanupConfirmed -> R.string.expert_platform_start_failed_cleanup
                rollbackAttempted -> R.string.expert_platform_start_failed_restore_failed
                else -> R.string.expert_platform_start_failed
            }
            throw IllegalStateException(context.getString(message), failure)
        }
    }

    override suspend fun apply(program: PolicyProgram, expected: TunIdentity): ExpertTunnelAck = mutex.withLock {
        check(tun == expected) { "Expert TUN identity changed before policy apply" }
        requireOwnedTun()
        val source = workspace.get() ?: error("Expert workspace has not been initialized")
        require(source.saved.revision == program.revision) { "Expert workspace revision changed before apply" }
        require(AndroidProtectedRuleGate.supports(program, Build.VERSION.SDK_INT)) {
            context.getString(R.string.expert_platform_package_protection_unavailable)
        }
        val defaults = settings.settings.first().engineDefaults
        val assembled = withContext(Dispatchers.Default) {
            PolicyConfigAssembler.assemble(
                source, program, EnginePlatform.ANDROID, defaults = defaults, ruleSetDirectory = simpleController.ruleSetDirectoryPath,
                probeUrl = ConfigAssembler.ANDROID_PROBE_URL,
            )
        }
        require(assembled.isValid) { assembled.errors.joinToString("; ") }
        val fingerprint = restartFingerprint(source, defaults, assembled)
        invalidateRestartEligibility()
        mutableRestoreWarning.value = null
        val previous = nativeAck ?: error("Expert native session has no acknowledgement")
        val session = native ?: error("Expert native session is not running")
        val actual = try {
            withContext(Dispatchers.IO) { session.apply(previous, program.revision, assembled.policyJson, assembled.exitManifestJson) }
        } catch (failure: Exception) {
            val observed = withContext(NonCancellable + Dispatchers.IO) {
                runCatching { runningAcknowledgement(session.status()) }.getOrNull()
            }
            if (observed == previous) {
                if (failure is CancellationException) throw failure
                LerNetLog.e(TAG, "Expert atomic preparation was rejected", failure)
                throw IllegalStateException(context.getString(R.string.expert_platform_apply_rejected), failure)
            }
            if (observed != null &&
                observed.instanceId == previous.instanceId &&
                observed.interfaceId == previous.interfaceId &&
                observed.revision == program.revision &&
                failure !is CancellationException
            ) {
                observed
            } else {
                throw ExpertStateUncertainException(context.getString(R.string.expert_platform_apply_uncertain), failure)
            }
        }
        requireOwnedTun()
        if (actual.instanceId != previous.instanceId || actual.interfaceId != previous.interfaceId) {
            scope.launch { signalLost("native_interface_changed") }
            throw ExpertStateUncertainException(context.getString(R.string.expert_platform_apply_uncertain))
        }
        if (actual.revision != program.revision) {
            throw ExpertStateUncertainException(context.getString(R.string.expert_platform_apply_uncertain))
        }
        nativeAck = actual
        exitTags = assembled.exitTags
        tagBindings.putAll(exitTags.entries.associate { it.value to it.key })
        exitGenerations.clear()
        appliedSnapshot = NativeAppliedSnapshot(session, actual, expected, serviceTun!!, assembled.exitTags)
        promoteRestartEligibility(fingerprint, actual.revision)
        ExpertTunnelAck(expected, actual.revision)
    }

    private suspend fun restartFingerprint(
        source: PolicyWorkspace,
        defaults: EngineDefaults,
        assembled: app.lernet.engine.policy.PolicyAssembledConfig,
    ): String = withContext(Dispatchers.IO) {
        ExpertRestartFingerprint.calculate(
            source,
            defaults,
            listOf(
                BuildConfig.VERSION_NAME, LibboxNative.PINNED_VERSION, Build.VERSION.SDK_INT.toString(),
                ConfigAssembler.ANDROID_PROBE_URL, assembled.ingressJson, assembled.policyJson, assembled.exitManifestJson,
            ),
            ExpertRestartFingerprint.ruleSetDigests(simpleController.ruleSetDirectoryPath),
        )
    }

    private suspend fun invalidateRestartEligibility() {
        try {
            withContext(Dispatchers.IO) { restartJournal.invalidate() }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            LerNetLog.e(TAG, "Expert restart eligibility could not be invalidated (${failure.javaClass.simpleName})")
            val explanation = context.getString(R.string.expert_platform_restart_invalidation_failed)
            mutableRestoreWarning.value = explanation
            throw IllegalStateException(explanation)
        }
    }

    private suspend fun promoteRestartEligibility(fingerprint: String, revision: Long) {
        val durable = withContext(NonCancellable + Dispatchers.IO) { restartJournal.promoteActualAck(fingerprint, revision) }
        if (!durable) {
            // The native ACK remains truthful. Only future automatic restoration is denied.
            mutableRestoreWarning.value = context.getString(R.string.expert_platform_restart_promotion_failed)
            LerNetLog.e(TAG, "Expert native ACK is active but its restart fingerprint was not persisted")
        }
    }

    override suspend fun stop(expected: TunIdentity?) {
        if (expected != null && tun != null && tun != expected) return
        operationEpoch.incrementAndGet()
        stopping = true
        appliedSnapshot = null
        // Native close cancels its session context. Do not wait for a long preparation/probe
        // under the Kotlin operation mutex before issuing that cancellation.
        var failure: Throwable? = null
        try {
            withContext(NonCancellable + Dispatchers.IO) { native?.close() }
        } catch (closeFailure: Throwable) {
            failure = closeFailure
        }
        try {
            mutex.withLock { withContext(NonCancellable) { cleanup() } }
        } catch (cleanupFailure: Throwable) {
            if (failure == null) failure = cleanupFailure else failure.addSuppressed(cleanupFailure)
        }
        failure?.let { throw IllegalStateException(context.getString(R.string.expert_platform_close_uncertain), it) }
    }

    override suspend fun wakeExit(key: ExpertExitKey, generation: Long, lifecycle: ExitLifecyclePolicy) = mutex.withLock {
        requireOwnedTun()
        if (generation < (exitGenerations[key] ?: Long.MIN_VALUE)) return@withLock
        exitGenerations[key] = generation
        val tag = exitTags[key] ?: error("Expert exit is absent from the applied native manifest")
        val ack = nativeAck ?: error("Expert native session has no acknowledgement")
        withContext(Dispatchers.IO) { (native ?: error("Expert session is not running")).wakeExit(ack, tag) }
    }

    override suspend fun sleepExit(key: ExpertExitKey, generation: Long) = mutex.withLock {
        requireOwnedTun()
        if (generation < (exitGenerations[key] ?: Long.MIN_VALUE)) return@withLock
        exitGenerations[key] = generation
        val tag = exitTags[key] ?: return@withLock
        val ack = nativeAck ?: error("Expert native session has no acknowledgement")
        withContext(Dispatchers.IO) { (native ?: error("Expert session is not running")).sleepExit(ack, tag) }
    }

    override suspend fun recoverExit(key: ExpertExitKey, generation: Long, lifecycle: ExitLifecyclePolicy) = mutex.withLock {
        requireOwnedTun()
        if (generation < (exitGenerations[key] ?: Long.MIN_VALUE)) return@withLock
        exitGenerations[key] = generation
        val tag = exitTags[key] ?: error("Expert exit is absent from the applied native manifest")
        val ack = nativeAck ?: error("Expert native session has no acknowledgement")
        withContext(Dispatchers.IO) { (native ?: error("Expert session is not running")).recoverExit(ack, tag) }
        // Native rebuilds only this protocol gate. A subsequent native HTTPS result confirms
        // recovery; neither a Ready flag nor a server TCP ping proves tunnel health.
    }

    override suspend fun probeExit(key: ExpertExitKey, timeoutMs: Long): ExpertProbeResult {
        require(timeoutMs > 0) { "Expert probe requires a positive timeout" }
        val snapshot = appliedSnapshot ?: error(context.getString(R.string.expert_platform_native_stopped))
        check(!stopping && serviceFailure == null && snapshot.serviceIdentity == ExpertVpnSession.identity) {
            context.getString(R.string.expert_platform_tunnel_lost)
        }
        val tag = snapshot.tags[key] ?: return ExpertProbeResult(null, context.getString(R.string.expert_platform_exit_missing))
        val raw = withContext(Dispatchers.IO) {
            snapshot.session.probeExit(snapshot.ack, tag, ConfigAssembler.ANDROID_PROBE_URL, timeoutMs.coerceAtMost(PROBE_TIMEOUT_MS))
        }
        if (appliedSnapshot !== snapshot || stopping || serviceFailure != null || snapshot.serviceIdentity != ExpertVpnSession.identity) {
            return ExpertProbeResult(null, context.getString(R.string.expert_platform_probe_stale))
        }
        val body = ExpertNativeJson.objectValue(raw, 8_192, "Native Expert probe response")
        return ExpertProbeResult(
            ExpertNativeJson.long(body, "https_latency_ms")?.takeIf { it > 0 },
            ExpertNativeJson.string(body, "reason")?.let(::nativeReason),
        )
    }

    private fun requireOwnedTun() {
        check(serviceFailure == null && serviceTun != null && serviceTun == ExpertVpnSession.identity) {
            context.getString(R.string.expert_platform_tunnel_lost)
        }
    }

    private suspend fun cleanup() {
        stopping = true
        appliedSnapshot = null
        polling?.cancel()
        polling = null
        val session = native
        val reservation = token
        // Before reservation, only Simple owns its service. A newer Simple intent may
        // already be using it, so Expert cleanup cannot stop an unreserved service.
        if (session == null && reservation == null) return
        withContext(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                session?.close()
            } catch (closeFailure: Throwable) {
                failure = closeFailure
            }
            try {
                // Android can keep a system-bound VpnService alive until its original PFD
                // closes. Explicitly close that generation instead of waiting for onDestroy.
                // Native's duplicated descriptor has an independent confirmation above.
                check(reservation != null) { "Expert native session has no VPN reservation" }
                withContext(Dispatchers.Main.immediate) {
                    val service = VpnRuntime.current() as? LerNetVpnService
                    if (service != null && service.ownsExpert(reservation)) service.stopExpert(reservation)
                    check(ExpertVpnSession.serviceCloseConfirmed(reservation)) {
                        "Expert VPN service descriptor closure is unconfirmed"
                    }
                    // Release in the same Main turn: an older queued onCreate must not
                    // attach between the empty-service confirmation and lease release.
                    if (failure == null) {
                        check(ExpertVpnSession.release(reservation)) { "Expert VPN reservation changed before cleanup completed" }
                    }
                }
            } catch (serviceCloseFailure: Throwable) {
                if (failure == null) failure = serviceCloseFailure else failure?.addSuppressed(serviceCloseFailure)
            }
            failure?.let { throw IllegalStateException(context.getString(R.string.expert_platform_close_uncertain), it) }
            native = null
            token = null
            nativeAck = null
            tun = null
            serviceTun = null
            trackedFlows.clear()
            exitTags = emptyMap()
            tagBindings.clear()
            observedNetworkEpoch = null
            exitGenerations.clear()
        }
    }

    private fun startPolling() {
        polling?.cancel()
        polling = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(1_000L)
                try {
                    mutex.withLock {
                        val session = native ?: return@launch
                        requireOwnedTun()
                        receiveStatus(session.status())
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    signalLost(SecretRedactor.redact(failure.message.orEmpty()))
                    return@launch
                }
            }
        }
    }

    private suspend fun receiveStatus(raw: String) {
        val body = ExpertNativeJson.objectValue(raw, 2_000_000, "Native Expert status")
        val previous = nativeAck ?: return
        val actual = ExpertNativeAck.decode(
            JsonObject(
                body.filterKeys {
                    it in setOf("instance_id", "interface_id", "revision")
                }
            ).toString()
        )
        check(actual.instanceId == previous.instanceId && actual.interfaceId == previous.interfaceId) {
            "Native Expert status reports a changed TUN"
        }
        check(ExpertNativeJson.boolean(body, "running") == true) {
            ExpertNativeJson.string(body, "stop_reason")?.let(::nativeReason)
                ?: context.getString(R.string.expert_platform_native_stopped)
        }
        check(actual.revision == previous.revision) {
            "Native Expert reports an unacknowledged policy revision"
        }
        tun?.let {
            mutableEvents.emit(
                ExpertBackendEvent.RetiredCleanupSnapshot(
                    app.lernet.engine.policy.ExpertNativeStatusEvidence.retiredCleanupFailures(body), it, actual.revision,
                )
            )
        }
        val networkEpoch = ExpertNativeJson.long(body, "network_epoch")?.takeIf { it >= 0 }
        if (networkEpoch != null) {
            val before = observedNetworkEpoch
            if (before == null || networkEpoch > before) observedNetworkEpoch = networkEpoch
            if (before != null && networkEpoch > before) {
                tun?.let { mutableEvents.emit(ExpertBackendEvent.NetworkChanged(it, actual.revision, networkEpoch)) }
            }
        }
        val exits = body["exits"] as? JsonArray ?: JsonArray(emptyList())
        require(exits.size <= 10_000) { "Native Expert returned too many physical exits" }
        val flows = body["flows"] as? JsonArray ?: JsonArray(emptyList())
        require(flows.size <= 500) { "Native Expert returned too many tracked flows" }
        // Removed physical exits remain visible while native drains their sockets. Keep the
        // binding for retained historical observations too, then prune after native eviction.
        val presentTags = buildSet {
            addAll(exitTags.values)
            exits.mapNotNullTo(this) { (it as? JsonObject)?.let { row -> ExpertNativeJson.string(row, "tag") } }
            flows.mapNotNullTo(this) { (it as? JsonObject)?.let { row -> ExpertNativeJson.string(row, "outbound") } }
        }
        tagBindings.keys.retainAll(presentTags)
        val byTag = tagBindings.toMap()
        val exitStates = mutableListOf<ExpertExitState>()
        for (entry in exits) {
            val exit = entry as? JsonObject ?: continue
            val key = ExpertNativeJson.string(exit, "tag")?.let(byTag::get) ?: continue
            val phase = ExpertNativeStatusEvidence.exitPhase(
                ExpertNativeJson.string(exit, "phase"), ExpertNativeJson.string(exit, "health"),
            )
            exitStates += ExpertExitState(
                key = key,
                phase = phase,
                latencyMs = ExpertNativeJson.long(exit, "latency_ms")?.takeIf { it > 0 },
                reason = ExpertNativeJson.string(exit, "reason")?.let(::nativeReason),
                activeFlows = ExpertNativeJson.int(exit, "active_flows")?.coerceAtLeast(0) ?: 0,
                pendingFlows = ExpertNativeJson.int(exit, "pending_flows")?.coerceAtLeast(0) ?: 0,
                lastCheckMs = ExpertNativeJson.long(exit, "last_check_ms")?.takeIf { it > 0 },
            )
        }
        mutableEvents.emit(ExpertBackendEvent.ExitsSnapshot(exitStates, identity = tun, revision = actual.revision))
        val folders = body["folders"] as? JsonArray ?: JsonArray(emptyList())
        require(folders.size <= 10_000) { "Native Expert returned too many folder gates" }
        val selectedFolders = buildMap {
            for (entry in folders) {
                val folder = entry as? JsonObject ?: continue
                val tag = ExpertNativeJson.string(folder, "tag") ?: continue
                val selected = ExpertNativeJson.string(folder, "selected_tag")?.let(byTag::get) ?: continue
                put(tag, selected)
            }
        }
        mutableEvents.emit(ExpertBackendEvent.FolderSelectionsSnapshot(selectedFolders, identity = tun, revision = actual.revision))
        val visibleIds = flows.mapNotNull { (it as? JsonObject)?.let { row -> ExpertNativeJson.long(row, "id") } }
            .filter { it > 0 }.map(Long::toString).toSet()
        trackedFlows.keys.retainAll(visibleIds)
        for (entry in flows) {
            val flow = entry as? JsonObject ?: continue
            val id = ExpertNativeJson.long(flow, "id")?.takeIf { it > 0 }?.toString() ?: continue
            val destination = ExpertNativeJson.string(flow, "destination") ?: continue
            val key = ExpertNativeJson.string(flow, "outbound")?.let(byTag::get)
            val closedValue = ExpertNativeJson.boolean(flow, "closed")
            val closed = closedValue == true
            val state = ExpertNativeJson.string(flow, "state")
            val rule = ExpertNativeJson.string(flow, "rule")
            val reason = ExpertNativeJson.string(flow, "reason")?.let(::nativeReason)
            val packages = ExpertNativeJson.strings(flow["packages"], 128).filter(String::isNotBlank).distinct()
            val application = ExpertNativeJson.string(flow, "process")?.takeIf(String::isNotBlank)
                ?: packages.takeIf { it.isNotEmpty() }?.joinToString(" / ")
            if (key != null && closedValue == false && !trackedFlows.containsKey(id)) {
                trackedFlows[id] = key
                mutableEvents.emit(ExpertBackendEvent.UserTraffic(key, id, identity = tun))
            }
            if (closed) trackedFlows.remove(id)?.let { mutableEvents.emit(ExpertBackendEvent.FlowClosed(it, id, identity = tun)) }
            mutableEvents.emit(
                ExpertBackendEvent.Observation(
                    ExpertConnectionObservation(
                        id = id,
                        application = application,
                        destination = destination,
                        protocol = ExpertNativeJson.string(flow, "network") ?: "",
                        nodeIds = ExpertNativeJson.strings(flow["node_ids"], 256),
                        exit = key,
                        decision = listOfNotNull(rule, state, reason).filter { it.isNotBlank() }.joinToString(" · "),
                        uploadedBytes = ExpertNativeJson.long(flow, "upload_bytes")?.coerceAtLeast(0) ?: 0,
                        downloadedBytes = ExpertNativeJson.long(flow, "download_bytes")?.coerceAtLeast(0) ?: 0,
                        active = closedValue?.not(),
                        startedAtMs = ExpertNativeJson.long(flow, "started_ms")?.takeIf { it > 0 },
                        policyRevision = ExpertNativeJson.long(flow, "revision")?.takeIf { it >= 0 },
                    ),
                    identity = tun,
                ),
            )
        }
    }

    private suspend fun signalLost(reason: String) {
        if (stopping) return
        val identity = tun ?: return
        if (!lossReported.compareAndSet(false, true)) return
        val safe = SecretRedactor.redact(reason)
        LerNetLog.w(TAG, "Expert native session lost: $safe")
        mutableEvents.emit(ExpertBackendEvent.TunnelLost(identity, context.getString(R.string.expert_platform_tunnel_lost)))
    }

    private fun identity(ack: ExpertNativeAck, owned: TunIdentity): TunIdentity =
        TunIdentity(ack.instanceId, "${ack.interfaceId}:${owned.instanceId}")

    private fun runningAcknowledgement(raw: String): ExpertNativeAck {
        val body = ExpertNativeJson.objectValue(raw, 2_000_000, "Native Expert status")
        check(ExpertNativeJson.boolean(body, "running") == true) {
            context.getString(R.string.expert_platform_native_stopped)
        }
        val identityFields = body.filterKeys { it in setOf("instance_id", "interface_id", "revision") }
        return ExpertNativeAck.decode(JsonObject(identityFields).toString())
    }

    private fun nativeReason(reason: String): String = when (reason) {
        "exit_sleeping" -> context.getString(R.string.expert_platform_exit_sleeping)
        else -> expertNativeFailureExplanation(reason)
    }

    private data class NativeAppliedSnapshot(
        val session: ExpertNativeSession,
        val ack: ExpertNativeAck,
        val identity: TunIdentity,
        val serviceIdentity: TunIdentity,
        val tags: Map<ExpertExitKey, String>,
    )

    companion object {
        private const val TAG = "LerNet.Expert"
        private const val PROBE_TIMEOUT_MS = 45_000L
    }
}
