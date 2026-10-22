package app.lernet.engine

import app.lernet.config.net.AppTraffic
import app.lernet.engine.policy.AppTrafficRoute

import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.Profile
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.config.net.LocalProxyAccess
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.compile.DnsBlock
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.SniffRematch
import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.live.ChannelWatch
import app.lernet.engine.live.LiveConn
import app.lernet.engine.live.LiveConnFormat
import app.lernet.engine.live.LiveFeed
import app.lernet.engine.live.LiveVia
import app.lernet.engine.live.LiveConnStatus
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.log.HardStopReason
import app.lernet.engine.net.DialOnlyHopTracer
import app.lernet.engine.net.HopPath
import app.lernet.engine.net.HopStop
import app.lernet.engine.net.HopTracer
import app.lernet.engine.net.ImmediateSuccessDialer
import app.lernet.engine.net.OutboundDialer
import app.lernet.engine.net.OutboundEndpoint
import app.lernet.engine.net.VpnGuard
import app.lernet.engine.policy.ByteGrowthGate
import app.lernet.engine.policy.ConfigPenalties
import app.lernet.engine.policy.HandshakeBudget
import app.lernet.engine.policy.HandshakeKey
import app.lernet.engine.policy.RestartLog
import app.lernet.engine.policy.ConnectionPolicyMachine
import app.lernet.engine.policy.DnsHealthAction
import app.lernet.engine.policy.DnsHealthGate
import app.lernet.engine.policy.MachineContext
import app.lernet.engine.policy.MachineState
import app.lernet.engine.policy.ManualFailoverGroup
import app.lernet.engine.policy.PolicyCommand
import app.lernet.engine.policy.PolicyEvent
import app.lernet.engine.policy.ReconnectSettings
import app.lernet.engine.redact.LerNetLog
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RoutePlatform
import app.lernet.routing.RuleNode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.lernet.engine.policy.BlockingDetector
import app.lernet.engine.policy.RecheckJitter
import app.lernet.engine.policy.FlowSample
import app.lernet.engine.policy.TunnelProbeSchedule
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionController(
    private val engine: BoxEngine,
    private val scope: CoroutineScope,
    private val machine: ConnectionPolicyMachine = ConnectionPolicyMachine(),
    private val hardStopTimeoutMs: Long = HARD_STOP_TIMEOUT_MS,
    private val outboundDialer: OutboundDialer = ImmediateSuccessDialer,
    private val probeTimeoutMs: Int = PROBE_TIMEOUT_MS,
    private val l7UrlTestEnabled: Boolean = DEFAULT_L7_URL_TEST_ENABLED,
    private val tunnelHealthProbe: (suspend () -> Result<Long>)? = null,
    /** When to check the tunnel: only at start, after a network change, or on unanswered traffic. */
    private val tunnelProbeSchedule: () -> TunnelProbeSchedule = { TunnelProbeSchedule() },
    /** Pause between folder candidates while looking for a live one: sequential, never a burst. */
    private val candidateGapMs: () -> Long = { app.lernet.engine.policy.LinkJudge.candidateGapMs() },
    val liveFeed: LiveFeed = LiveFeed(),
    private val ruleSetDirectory: String = "",
    /** Hop walk only — never Main. Tests inject the test dispatcher. */
    private val pathDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** The platform excludes the VPN itself. Offline is not a reason to rebuild its TUN. */
    private val underlayAvailable: () -> Boolean = { true },
    private val persistSessionIntent: suspend (SimpleSessionConfig?) -> Unit = {},
    /** Background checks of folder backups while connected; null disables them (tests). */
    private val backupProbe: app.lernet.engine.net.BackupProbe? = null,
    private val backupDelayMs: () -> Long = { app.lernet.engine.policy.BackupRotation.nextDelayMs() },
    /** Configs that died and rest; the app passes a persisted one so the list survives restarts. */
    val penalties: ConfigPenalties = ConfigPenalties(),
    /** LerNET's own new connections per server+SNI ([HandshakeBudget]); shared with «Проверить все». */
    val handshakeBudget: HandshakeBudget = HandshakeBudget(),
    /** Wall clock for penalties and the handshake budget; virtual in tests. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var engineReady = false
    private var machineState = MachineState.idle()
    private var context = machineContext(ReconnectSettings(), app.lernet.engine.policy.FailoverSettings(), null)
    /** Server+SNI of every config this session knows, for the handshake budget. */
    private val outboundKeys = java.util.concurrent.ConcurrentHashMap<String, HandshakeKey>()
    /** Folder configs carried in the running core only to be checked: outbound id → tag. */
    @Volatile private var standbyTags: Map<String, String> = emptyMap()
    /** The core refused the config with standby outbounds once: run without them for this session. */
    @Volatile private var standbyRefused = false
    private var retryJob: Job? = null
    /** Wall clock of the last network appearance/change; null until the first change. */
    @Volatile private var networkChangedAtMs: Long? = null
    /** A long random recheck (all configs unavailable) that is still waiting; cut short when the network returns. */
    private var pendingRecheck: PolicyCommand.ScheduleRetry? = null
    /** Replies through the VPN exit only; direct sites never reset the failure counter. */
    private val proxiedReplies = app.lernet.engine.live.ProxiedReplies()
    @Volatile private var connectionFeedSeen = false
    private var healthJob: Job? = null
    private var dnsHealthJob: Job? = null
    private val dnsHealth = DnsHealthGate()
    private val byteGrowth = ByteGrowthGate()
    private val rateSampler = RateSampler()
    private var connectTimeoutJob: Job? = null
    private var probeJob: Job? = null
    private var tunnelHealthJob: Job? = null
    private var backupJob: Job? = null
    private val backupRotation = app.lernet.engine.policy.BackupRotation()
    /** What background checks learned about backups; valid for the current network only. */
    private val backupHealth = java.util.concurrent.ConcurrentHashMap<String, app.lernet.engine.policy.BackupHealth>()
    @Volatile private var probeSchedule: TunnelProbeSchedule? = null
    /** Monotonic clock of the health ticker; virtual in tests. */
    @Volatile private var healthClockMs = 0L
    private val blockingDetector = BlockingDetector()
    private var groupProbeJob: Job? = null
    private var failoverSwitchJob: Job? = null
    private var stopJob: Job? = null
    private var pendingEndpoint: OutboundEndpoint? = null
    private var pendingOutboundId: String? = null
    private var pendingLogLevel: String = "warn"
    private var pendingDefaults: EngineDefaults = EngineDefaults()
    /** Proxy mode credentials of the current session; a reconnect keeps them. */
    private var pendingUserProxy: LocalProxyAccess? = null
    private var resolveFailoverProfile: (suspend (String) -> Pair<Profile, List<RuleNodeRecord>>?)? = null
    /** Names of profiles this session ran, so «Что пробовали» can name the config that failed first. */
    private val profileNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var onFailoverSelected: (suspend (String) -> Unit)? = null
    private var pendingProxyTag: String? = null
    private var pendingDnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY
    private val userIntentEpoch = AtomicLong(0)
    private var pendingRestoreGate: (() -> Boolean)? = null
    private var loggedTunBytes: Boolean = false
    private var dnsOkInWindow: Boolean = false
    private var dnsOkSession: Boolean = false
    private var watchdogMisses: Int = 0
    private var statusTicks: Int = 0
    private var channel: ChannelHealth = ChannelHealth.UNKNOWN
    private var hopUp: Boolean = false
    private var sawDeadline: Boolean = false
    private var hopCheckedThisWindow: Boolean = false
    private var lastConnectionsOut: Int = 0
    private var pathTraced: Boolean = false
    private val pathGeneration = AtomicInteger(0)
    private var pathJob: Job? = null
    private var shownHops: List<HopStop> = emptyList()
    private var shownHopHost: String = ""
    private var shownHopTimedOut: Boolean = false
    private var shownHopChecked: Boolean = false
    private var shownHopRunning: Boolean = false
    private var shownPipeSilent: Int = 0
    private var shownPipeTunnel: Int = 0

    /** Production replaces this with the ICMP walk. Tests keep the single dial target. */
    var pathTracer: HopTracer = DialOnlyHopTracer

    private val _snapshot = MutableStateFlow(machineState.snapshot)
    val snapshot: StateFlow<ConnectionSnapshot> = _snapshot.asStateFlow()

    val engineAvailable: Boolean get() = engine.isNativeAvailable

    val engineVersion: String get() = engine.engineVersion
    val ruleSetDirectoryPath: String get() = ruleSetDirectory

    init {
        scope.launch {
            engine.events.collect { event ->
                runCatching { onEngineEvent(event) }
                    .onFailure { error ->
                        LerNetLog.e(TAG, "engine event handling failed: ${error.message}", error)
                    }
            }
        }
    }

    fun updateSettings(
        reconnect: ReconnectSettings,
        failoverEnabled: Boolean,
        group: ManualFailoverGroup?,
    ) {
        context = machineContext(reconnect, app.lernet.engine.policy.FailoverSettings(failoverEnabled, group?.id), group)
    }

    private fun machineContext(
        reconnect: ReconnectSettings,
        failover: app.lernet.engine.policy.FailoverSettings,
        group: ManualFailoverGroup?,
    ): MachineContext = MachineContext(
        reconnect = reconnect,
        failover = failover,
        group = group,
        msSinceNetworkChange = { networkChangedAtMs?.let { clock() - it } ?: Long.MAX_VALUE },
        nowMs = clock,
        penalties = penalties,
        budgetWaitMs = { id -> outboundKeys[id]?.let { handshakeBudget.waitMs(it, clock()) } ?: 0L },
    )

    /** How long until LerNET may open one more connection to the active server. */
    fun activeBudgetWaitMs(): Long = activeKey()?.let { handshakeBudget.waitMs(it, clock()) } ?: 0L

    /** Takes one new connection to the active server from the budget; false = not now. */
    fun takeActiveBudget(): Boolean = activeKey()?.let { handshakeBudget.tryTake(it, clock()) } ?: true

    /** How long [key]'s budget still forbids one more new connection (a check of another server). */
    fun budgetWaitMs(key: HandshakeKey): Long = handshakeBudget.waitMs(key, clock())

    /** Takes one new connection to [key]'s server from the budget; false = not now. */
    fun takeBudget(key: HandshakeKey): Boolean = handshakeBudget.tryTake(key, clock())

    private fun activeKey(): HandshakeKey? = _snapshot.value.activeOutboundId?.let(outboundKeys::get)

    fun setFailoverHandlers(
        resolve: suspend (String) -> Pair<Profile, List<RuleNodeRecord>>?,
        onSelected: suspend (String) -> Unit,
    ) {
        resolveFailoverProfile = resolve
        onFailoverSelected = onSelected
    }

    private suspend fun onEngineEvent(event: EngineEvent) {
        when (event) {
            EngineEvent.Started -> {
                engineReady = true
                dispatch(PolicyEvent.EngineStarted)
                if (_snapshot.value.state == ConnectionState.CONNECTING ||
                    _snapshot.value.state == ConnectionState.RECONNECTING
                ) {
                    startOutboundProbe()
                }
            }
            is EngineEvent.Status -> onStatus(event)
            is EngineEvent.Failed -> mutex.withLock {
                // Native reports Failed only after abandoning the service. Unlike a failed
                // health probe, this needs a new start, including when the underlay is offline.
                engineReady = false
                dispatchLocked(PolicyEvent.EngineFailed(event.cause))
            }
            is EngineEvent.LogLine -> {
                LerNetLog.i(TAG, event.line)
                SniffRematch.fromLibboxLine(event.line)?.let { LerNetLog.i(TAG, it) }
                noteBlocking(blockingDetector.onLogLine(healthClockMs, event.line))
                when {
                    event.line.contains("dns query ok") -> markDnsOk(event.line)
                    event.line.contains("dns query fail") -> dnsHealth.onDnsFail()
                    event.line.contains("context deadline exceeded") -> sawDeadline = true
                }
            }
            is EngineEvent.DnsAlive -> markDnsOk("dns path alive answers=${event.answers}")
            is EngineEvent.Connections -> {
                connectionFeedSeen = true
                proxiedReplies.observe(event.reset, event.rows)
                liveFeed.apply(event.reset, event.rows)
                noteTunnelSilence(event.rows)
                noteBlocking(
                    blockingDetector.onFlows(
                        healthClockMs,
                        event.rows.map {
                            FlowSample(it.id, it.via == LiveVia.PROXY, it.uplink, it.downlink, it.status == LiveConnStatus.OPEN)
                        },
                        complete = event.reset,
                    ),
                )
            }
        }
    }

    private suspend fun onStatus(event: EngineEvent.Status) {
        if (!loggedTunBytes && (event.uplinkTotal > 0L || event.downlinkTotal > 0L)) {
            loggedTunBytes = true
            LerNetLog.i(
                TAG,
                "tun-in first bytes up=${event.uplinkTotal} down=${event.downlinkTotal} conns=${event.connectionsOut}",
            )
        }
        val rate = rateSampler.sample(
            reportedUpBps = event.uplinkBps,
            reportedDownBps = event.downlinkBps,
            uplinkTotal = event.uplinkTotal,
            downlinkTotal = event.downlinkTotal,
            nowMs = System.currentTimeMillis(),
        )
        statusTicks += 1
        lastConnectionsOut = event.connectionsOut
        if (statusTicks % STATUS_LOG_EVERY == 0) {
            LerNetLog.i(
                TAG,
                "status up=${rate.uplinkBps}B/s down=${rate.downlinkBps}B/s " +
                    "upTotal=${event.uplinkTotal} downTotal=${event.downlinkTotal} " +
                    "conns=${event.connectionsOut} dnsOk=$dnsOkInWindow " +
                    "libboxUp=${event.uplinkBps} libboxDown=${event.downlinkBps}",
            )
        }
        probeSchedule?.let { schedule ->
            val replies = if (connectionFeedSeen) proxiedReplies.total else event.downlinkTotal
            synchronized(schedule) { schedule.onTraffic(healthClockMs, event.uplinkTotal, replies) }
        }
        dispatch(
            PolicyEvent.EngineStatus(
                uplinkBps = rate.uplinkBps,
                downlinkBps = rate.downlinkBps,
                uplinkTotal = event.uplinkTotal,
                downlinkTotal = event.downlinkTotal,
                connectionsOut = event.connectionsOut,
                dnsOk = dnsOkSession,
                proxiedReplies = if (connectionFeedSeen) proxiedReplies.total else null,
            ),
        )
    }

    suspend fun connect(
        profile: Profile,
        nodes: List<RuleNodeRecord>,
        mode: RunMode,
        logLevel: String = "warn",
        defaults: EngineDefaults = EngineDefaults(),
        userProxy: LocalProxyAccess? = pendingUserProxy,
        reason: String = PolicyCommand.REASON_START,
    ) {
        val epoch = userIntentEpoch.incrementAndGet()
        CrashTrail.mark("controller.connect enter profile=${profile.id} mode=$mode")
        profileNames[profile.id] = profile.name
        val outbound = profile.selectedOutbound()
        if (outbound == null) {
            dispatch(PolicyEvent.EngineFailed(ConnectionCause.InvalidConfig(listOf("нет выбранного outbound"))))
            return
        }
        val compiled = try {
            RouteCompiler.compile(nodes.toRouting(), RoutePlatform.ANDROID)
        } catch (error: Throwable) {
            CrashTrail.recordFailure("RouteCompiler.compile", error)
            dispatch(PolicyEvent.EngineFailed(ConnectionCause.InvalidRouteTree(listOf(error.message ?: "compile"))))
            return
        }
        val effectiveLog = if (logLevel == "warn" || logLevel == "error") "info" else logLevel
        HandshakeKey.fromOutboundJson(outbound.singBoxJson)?.let { outboundKeys[outbound.id] = it }
        val standby = if (standbyRefused) emptyList() else standbyCandidates(outbound.id)
        val assembled = try {
            ConfigAssembler.assemble(
                outbound,
                compiled,
                mode,
                logLevel = effectiveLog,
                dnsJson = profile.dnsJson,
                dnsPolicy = profile.dnsPolicy,
                ruleSetDirectory = ruleSetDirectory,
                defaults = defaults,
                userProxy = userProxy,
                // New random login/password for LerNET's own requests on every connection.
                appProxy = LocalProxyAccess.random(ConfigAssembler.APP_PROXY_PORT),
                standby = standby,
            )
        } catch (error: Throwable) {
            CrashTrail.recordFailure("ConfigAssembler.assemble", error)
            dispatch(
                PolicyEvent.EngineFailed(
                    ConnectionCause.InvalidConfig(listOf(error.message ?: "assemble")),
                ),
            )
            return
        }
        if (!assembled.isValid) {
            dispatch(
                PolicyEvent.EngineFailed(
                    ConnectionCause.InvalidRouteTree(assembled.errors),
                ),
            )
            return
        }
        mutex.withLock {
            if (userIntentEpoch.get() != epoch) return@withLock
            pendingRestoreGate = { userIntentEpoch.get() == epoch }
            pendingDnsPolicy = profile.dnsPolicy
            pendingEndpoint = OutboundEndpoint.parse(outbound.singBoxJson)
                ?: OutboundEndpoint.fromAssembled(assembled.json, assembled.proxyTag)
            pendingOutboundId = outbound.id
            pendingLogLevel = logLevel
            pendingDefaults = defaults
            pendingUserProxy = assembled.userProxy
            pendingProxyTag = assembled.proxyTag
            standbyTags = assembled.standbyTags
            resetConnectMarkersLocked()
            try {
                persistSessionIntent(SimpleSessionConfig(
                    profile.id, outbound.id, assembled.json, mode, logLevel, defaults, assembled.proxyTag, profile.dnsPolicy,
                ))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                LerNetLog.e(TAG, "Session intent write failed (${error.javaClass.simpleName})")
                dispatchLocked(PolicyEvent.EngineFailed(ConnectionCause.InvalidConfig(listOf("Не удалось сохранить намерение запуска VPN"))))
                return@withLock
            }
            if (userIntentEpoch.get() != epoch) return@withLock
            assembled.notes.forEach { LerNetLog.i(TAG, it) }
            CrashTrail.mark("controller.connect profile=${profile.id} mode=$mode l7Gate=$l7UrlTestEnabled")
            LerNetLog.i(TAG, "assembled json: ${assembled.json}")
            LerNetLog.i(TAG, "L7 probe tag=${pendingProxyTag ?: "?"} tcp=${pendingEndpoint?.label() ?: "missing"} log=$effectiveLog")
            dispatchLocked(
                PolicyEvent.StartRequested(
                    profileId = profile.id,
                    outboundId = outbound.id,
                    mode = mode,
                    compiledJson = assembled.json,
                    reason = reason,
                ),
            )
        }
    }

    /** Folder backups the running core carries, so a backup is checked by a real request through it. */
    private suspend fun standbyCandidates(activeOutboundId: String): List<app.lernet.config.model.NormalizedOutbound> {
        val group = context.group ?: return emptyList()
        if (!context.failover.enabled) return emptyList()
        val resolve = resolveFailoverProfile ?: return emptyList()
        return group.outboundIds.filter { it != activeOutboundId }.take(ConfigAssembler.MAX_STANDBY).mapNotNull { id ->
            val outbound = try {
                resolve(id)?.first?.selectedOutbound()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            } ?: return@mapNotNull null
            HandshakeKey.fromOutboundJson(outbound.singBoxJson)?.let { outboundKeys[id] = it }
            // The profile id is the folder id here; the assembler tags by this id.
            outbound.copy(id = id)
        }
    }

    suspend fun disconnect() {
        userIntentEpoch.incrementAndGet()
        mutex.withLock {
            clearSessionIntentAndDispatch(PolicyEvent.UserDisconnect)
        }
    }

    /** System restart reuses effective JSON, including the former DNS and outbound settings. */
    suspend fun resumePersistentSession(config: SimpleSessionConfig, stillDesired: () -> Boolean): Boolean = mutex.withLock {
        if (!stillDesired() || machineState.snapshot.state != ConnectionState.DISCONNECTED) return@withLock false
        val epoch = userIntentEpoch.incrementAndGet()
        pendingRestoreGate = { userIntentEpoch.get() == epoch && stillDesired() }
        pendingEndpoint = config.proxyTag?.let { OutboundEndpoint.fromAssembled(config.compiledJson, it) }
        pendingOutboundId = config.outboundId
        pendingLogLevel = config.logLevel
        pendingDefaults = config.defaults
        pendingProxyTag = config.proxyTag
        pendingDnsPolicy = config.dnsPolicy
        standbyTags = emptyMap()
        config.proxyTag?.let { keyFromAssembled(config.compiledJson, it) }?.let { outboundKeys[config.outboundId] = it }
        resetConnectMarkersLocked()
        dispatchLocked(
            PolicyEvent.StartRequested(config.profileId, config.outboundId, config.mode, config.compiledJson, reason = REASON_RESUME),
        )
        true
    }

    /** Stops Simple after capturing its actual active rules and outbound, as one controller operation. */
    suspend fun suspendForModeSwitch(stillDesired: () -> Boolean = { true }): SimpleModeRestorePoint? = mutex.withLock {
        check(stillDesired()) { "Mode switch was superseded before Simple suspension" }
        val current = machineState.snapshot
        val epoch = userIntentEpoch.incrementAndGet()
        val point = if (current.state == ConnectionState.CONNECTED &&
            current.compiledJson != null &&
            current.activeProfileId != null &&
            current.activeOutboundId != null
        ) {
            SimpleModeRestorePoint(
                current.activeProfileId, current.activeOutboundId, current.mode, current.compiledJson, epoch,
                pendingEndpoint, pendingLogLevel, pendingDefaults, pendingProxyTag, pendingDnsPolicy,
            )
        } else {
            null
        }
        dispatchLocked(PolicyEvent.UserDisconnect)
        point
    }

    /** Returns true only after the exact former profile/mode is connected again. */
    suspend fun restoreAfterModeSwitch(
        point: SimpleModeRestorePoint,
        stillDesired: () -> Boolean,
        timeoutMs: Long = 45_000,
    ): Boolean {
        require(timeoutMs > 0)
        var restorationEpoch: Long? = null
        var restored = false
        try {
            restored = withTimeout(timeoutMs) {
                // A queued old Stop must finish before a restored engine can be started.
                stopJob?.join()
                val accepted = mutex.withLock {
                    if (!stillDesired() ||
                        userIntentEpoch.get() != point.controllerEpoch ||
                        machineState.snapshot.state != ConnectionState.DISCONNECTED
                    ) {
                        return@withLock false
                    }
                    val epoch = userIntentEpoch.incrementAndGet()
                    restorationEpoch = epoch
                    pendingRestoreGate = { userIntentEpoch.get() == epoch && stillDesired() }
                    pendingEndpoint = point.endpoint
                    pendingOutboundId = point.outboundId
                    pendingLogLevel = point.logLevel
                    pendingDefaults = point.defaults
                    pendingProxyTag = point.proxyTag
                    pendingDnsPolicy = point.dnsPolicy
                    standbyTags = emptyMap()
                    resetConnectMarkersLocked()
                    dispatchLocked(
                        PolicyEvent.StartRequested(
                            point.profileId, point.outboundId, point.mode, point.compiledJson, reason = REASON_RESUME,
                        ),
                    )
                    true
                }
                if (!accepted) return@withTimeout false
                while (stillDesired() && userIntentEpoch.get() == restorationEpoch) {
                    val current = snapshot.value
                    if (current.state == ConnectionState.CONNECTED) {
                        val same =
                            current.activeProfileId == point.profileId &&
                                current.activeOutboundId == point.outboundId &&
                                current.mode == point.mode
                        if (!same) return@withTimeout false
                        val healthy = engine.probeOutbound(point.proxyTag ?: "proxy", L7_PROBE_URL, ACTIVE_PROBE_TIMEOUT_MS).isSuccess
                        return@withTimeout healthy && stillDesired() && userIntentEpoch.get() == restorationEpoch
                    }
                    if (current.state == ConnectionState.FAILED || current.state == ConnectionState.DISCONNECTED) return@withTimeout false
                    delay(25)
                }
                false
            }
            return restored
        } catch (_: TimeoutCancellationException) {
            return false
        } finally {
            if (!restored && restorationEpoch != null) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (userIntentEpoch.get() == restorationEpoch) {
                            userIntentEpoch.incrementAndGet()
                            dispatchLocked(PolicyEvent.UserDisconnect)
                        }
                    }
                }
            }
        }
    }

    suspend fun onPermissionDenied() {
        userIntentEpoch.incrementAndGet()
        mutex.withLock {
            clearSessionIntentAndDispatch(PolicyEvent.PermissionDenied)
        }
    }

    /** A disk error must never prevent the user's Stop or permission revocation. */
    private suspend fun clearSessionIntentAndDispatch(event: PolicyEvent) {
        try {
            persistSessionIntent(null)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            LerNetLog.e(TAG, "Session intent clear failed (${error.javaClass.simpleName})")
        } finally {
            dispatchLocked(event)
        }
    }

    suspend fun onEngineSignal(cause: ConnectionCause) {
        dispatch(PolicyEvent.EngineFailed(cause))
    }

    suspend fun onWatchdogMiss() {
        dispatch(PolicyEvent.WatchdogMiss)
    }

    suspend fun onProbeResult(aliveOutboundIds: Set<String>) {
        dispatch(PolicyEvent.ProbeCompleted(aliveOutboundIds))
    }

    private suspend fun dispatch(event: PolicyEvent) {
        mutex.withLock { dispatchLocked(event) }
    }

    suspend fun onUnderlayRestored() {
        if (!underlayAvailable()) return
        networkChangedAtMs = clock()
        // A new network may let resting configs through: they may be tried once more (the level stays).
        if (penalties.onNetworkChanged(clock())) LerNetLog.i(TAG, "network changed: resting configs may be tried again")
        // Bytes that stalled while the network was gone say nothing about filters.
        blockingDetector.reset()
        // Results from the previous network say nothing about this one.
        backupHealth.clear()
        backupRotation.reset()
        if (_snapshot.value.waitingForNetwork) _snapshot.value = _snapshot.value.copy(waitingForNetwork = false)
        if (_snapshot.value.state == ConnectionState.RECONNECTING && retryJob?.isActive == true) {
            // The configs looked unavailable while the phone itself had no network: do not sit
            // out the long random recheck, try again right after the network is back.
            mutex.withLock {
                val waiting = pendingRecheck ?: return@withLock
                if (retryJob?.isActive == true) execute(waiting.copy(delayMs = UNDERLAY_RETURN_RETRY_MS))
            }
        }
        if (!engineReady) return
        val current = _snapshot.value.state
        if (current == ConnectionState.CONNECTED) {
            armTrafficWatch()
            armDnsHealth()
            armTunnelHealth()
        } else if (current in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) && retryJob?.isActive != true) {
            startOutboundProbe()
            armConnectTimeout()
        }
    }

    private fun dispatchLocked(event: PolicyEvent) {
        val networkFailure = event == PolicyEvent.WatchdogMiss || event == PolicyEvent.DnsHealthTimeout ||
            event == PolicyEvent.ConnectTimeout || event is PolicyEvent.EngineFailed && event.cause.isRetryable()
        if (networkFailure && !underlayAvailable() &&
            (engineReady || event !is PolicyEvent.EngineFailed)
        ) {
            LerNetLog.w(TAG, "Нет исходной сети: сохраняем VPN/TUN и ждём восстановления")
            if (!_snapshot.value.waitingForNetwork) _snapshot.value = _snapshot.value.copy(waitingForNetwork = true)
            return
        }
        // A generic start exception while offline can be retried once the underlay returns.
        // Invalid configuration and permission failures keep their terminal classification.
        val effective = if (!underlayAvailable() && event is PolicyEvent.EngineFailed && event.cause is ConnectionCause.EngineStartFailed) {
            PolicyEvent.EngineFailed(ConnectionCause.DialFailure(event.cause.detail))
        } else event
        val (next, commands) = machine.reduce(machineState, context, effective)
        machineState = next
        val hops = _snapshot.value
        _snapshot.value = next.snapshot.copy(
            waitingForNetwork = hops.waitingForNetwork && !underlayAvailable(),
            // Cleared only by real evidence that traffic flows again, or by the user's stop: an engine
            // restart on the same dead config is not a recovery.
            tried = hops.tried.takeIf {
                next.snapshot.state != ConnectionState.DISCONNECTED &&
                    effective != PolicyEvent.TunnelVerified && effective != PolicyEvent.DnsPathOk &&
                    next.snapshot.activeProfileId == hops.activeProfileId
            },
            serverTcpMs = if (next.snapshot.activeProfileId == hops.activeProfileId &&
                next.snapshot.state != ConnectionState.DISCONNECTED &&
                next.snapshot.state != ConnectionState.FAILED
            ) {
                hops.serverTcpMs
            } else {
                null
            },
            channel = channel,
            hops = hops.hops,
            hopHost = hops.hopHost,
            hopTimedOut = hops.hopTimedOut,
            hopChecked = hops.hopChecked,
            hopRunning = hops.hopRunning,
            pipeSilentCount = shownPipeSilent,
            pipeTunnelCount = shownPipeTunnel,
        )
        // LerNET's own requests follow the connection: through VPN, refused, or direct when off.
        AppTraffic.set(AppTrafficRoute.of(next.snapshot.state, next.snapshot.compiledJson))
        commands.forEach { execute(it) }
    }

    private fun execute(command: PolicyCommand) {
        when (command) {
            is PolicyCommand.StartEngine -> {
                engineReady = false
                if (pendingOutboundId != null && command.outboundId != pendingOutboundId) {
                    failoverSwitchJob?.cancel()
                    failoverSwitchJob = scope.launch {
                        val target = try {
                            resolveFailoverProfile?.invoke(command.outboundId)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            LerNetLog.e(TAG, "failover profile lookup failed: ${error.message}", error)
                            null
                        }
                        if (target == null) {
                            dispatch(PolicyEvent.EngineFailed(ConnectionCause.FailoverExhausted(context.group?.name ?: "?")))
                        } else {
                            if (machineState.snapshot.state != ConnectionState.CONNECTING ||
                                machineState.snapshot.activeOutboundId != command.outboundId
                            ) {
                                return@launch
                            }
                            connect(
                                target.first, target.second, machineState.snapshot.mode, pendingLogLevel, pendingDefaults,
                                reason = command.reason,
                            )
                            if (machineState.snapshot.activeProfileId == target.first.id) {
                                onFailoverSelected?.invoke(target.first.id)
                            }
                        }
                    }
                    return
                }
                val json = stampedConfigJson()
                val mode = machineState.snapshot.mode
                armConnectTimeout()
                // Hop map is a sibling job — never awaited by tunnel dial / Connected.
                scheduleTrace(force = true)
                CrashTrail.mark("controller StartEngine mode=$mode jsonBytes=${json.length}")
                LerNetLog.i(TAG, RestartLog.line(command.reason, machineState.snapshot.attempt, 0L, "outbound=${command.outboundId}"))
                val restoreGate = pendingRestoreGate
                scope.launch {
                    if (restoreGate != null && !restoreGate()) return@launch
                    activeKey()?.let { handshakeBudget.record(it, clock()) }
                    runCatching { engine.start(json, mode) }
                        .onFailure { error -> onStartFailed(error, json, "controller.engine.start") }
                }
            }
            is PolicyCommand.StopEngine -> {
                engineReady = false
                retryJob?.cancel()
                healthJob?.cancel()
                dnsHealthJob?.cancel()
                connectTimeoutJob?.cancel()
                probeJob?.cancel()
                tunnelHealthJob?.cancel()
                backupJob?.cancel()
                groupProbeJob?.cancel()
                failoverSwitchJob?.cancel()
                dropInFlightPath()
                CrashTrail.mark(HardStopReason.crumb(command.reason))
                LerNetLog.i(TAG, HardStopReason.crumb(command.reason))
                if (stopJob?.isActive == true) {
                    return
                }
                stopJob = scope.launch { hardStopEngine() }
            }
            is PolicyCommand.ScheduleRetry -> {
                dropInFlightPath()
                retryJob?.cancel()
                healthJob?.cancel()
                tunnelHealthJob?.cancel()
                backupJob?.cancel()
                dnsHealthJob?.cancel()
                connectTimeoutJob?.cancel()
                groupProbeJob?.cancel()
                failoverSwitchJob?.cancel()
                scheduleTrace(force = true)
                val restoreGate = pendingRestoreGate
                pendingRecheck = command.takeIf { it.delayMs >= RecheckJitter.MIN_MS }
                LerNetLog.i(TAG, RestartLog.line(command.reason, command.attempt, command.delayMs, "outbound=${command.outboundId}"))
                if (pendingRecheck != null) {
                    LerNetLog.i(TAG, "Конфиг недоступен: перепроверка через ${RecheckJitter.label(command.delayMs)}")
                }
                retryJob = scope.launch {
                    delay(command.delayMs)
                    pendingRecheck = null
                    // An already scheduled retry must also respect a later network outage.
                    while (!underlayAvailable()) delay(1_000)
                    if (restoreGate != null && !restoreGate()) return@launch
                    armConnectTimeout()
                    loggedTunBytes = false
                    dnsOkInWindow = false
                    dnsOkSession = false
                    dnsHealth.resetPhaseForReload()
                    byteGrowth.reset()
                    rateSampler.reset()
                    watchdogMisses = 0
                    val json = stampedConfigJson()
                    val mode = machineState.snapshot.mode
                    CrashTrail.mark("controller ScheduleRetry mode=$mode jsonBytes=${json.length}")
                    activeKey()?.let { handshakeBudget.record(it, clock()) }
                    runCatching { engine.start(json, mode) }
                        .onFailure { error -> onStartFailed(error, json, "controller.engine.retry") }
                }
            }
            PolicyCommand.RequestCheck -> probeSchedule?.let { schedule ->
                synchronized(schedule) { schedule.onHint(healthClockMs) }
            }
            is PolicyCommand.HoldCheck -> {
                LerNetLog.i(TAG, "hold: core and live connections kept, next look in ${command.delayMs}ms")
                probeSchedule?.let { schedule -> synchronized(schedule) { schedule.planHold(healthClockMs, command.delayMs) } }
            }
            is PolicyCommand.ProbeGroup -> {
                connectTimeoutJob?.cancel()
                groupProbeJob?.cancel()
                groupProbeJob = scope.launch {
                    val resolve = resolveFailoverProfile
                    var winner: String? = null
                    val checked = mutableSetOf<String>()
                    val attempts = mutableListOf<app.lernet.engine.policy.FailoverAttempt>()
                    // The config that just failed goes first, with the reason the machine recorded.
                    _snapshot.value.let { failed ->
                        val name = failed.activeProfileId?.let(profileNames::get)
                        if (name != null) attempts += app.lernet.engine.policy.FailoverAttempt(
                            name, failedReason(failed.cause ?: ConnectionCause.TunnelHealthFailed("")), clock(),
                        )
                    }
                    if (resolve != null) {
                        // Backups verified by the background checks go first: the switch takes seconds.
                        val ordered = app.lernet.engine.policy.BackupHealth.order(command.outboundIds, backupHealth, clock())
                        for ((index, outboundId) in ordered.withIndex()) {
                            // One at a time with a random gap: never a burst of new connections.
                            if (index > 0) delay(candidateGapMs())
                            val profile = try {
                                resolve(outboundId)?.first
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                LerNetLog.w(TAG, "failover candidate lookup failed: ${error.message}", error)
                                null
                            } ?: continue
                            val json = profile.selectedOutbound()?.singBoxJson ?: continue
                            val result = checkCandidate(outboundId, json) ?: continue
                            checked += outboundId
                            profileNames[profile.id] = profile.name
                            if (result.isSuccess) {
                                winner = outboundId
                                break
                            }
                            val why = candidateFailure(result.exceptionOrNull())
                            attempts += app.lernet.engine.policy.FailoverAttempt(profile.name, why, clock())
                        }
                    }
                    dispatch(PolicyEvent.ProbeCompleted(winner?.let(::setOf) ?: emptySet(), checked))
                    if (winner == null && attempts.size > 0) mutex.withLock {
                        val wait = _snapshot.value.nextCheckAtMs?.let { (it - clock()).coerceAtLeast(0L) }?.let(RecheckJitter::label)
                        _snapshot.value = _snapshot.value.copy(
                            tried = app.lernet.engine.policy.FailoverReport.merge(_snapshot.value.tried, null, attempts, wait),
                        )
                    }
                }
            }
            is PolicyCommand.ShowBanner -> Unit
        }
    }

    private suspend fun hardStopEngine() {
        val finished = withTimeoutOrNull(hardStopTimeoutMs) {
            runCatching { engine.stop() }
        }
        when {
            finished == null -> {
                LerNetLog.e(TAG, "engine.stop timed out after ${hardStopTimeoutMs}ms, aborting")
                engine.abort()
            }
            finished.isFailure -> {
                val error = finished.exceptionOrNull()
                LerNetLog.e(TAG, "engine.stop failed: ${error?.message}", error)
                engine.abort()
            }
        }
    }

    /**
     * A real check of one folder config: an HTTPS request through it inside the running core (a full
     * handshake with its server), or, when the core is down or does not carry it, a TCP dial. Null when the
     * server's handshake budget is busy for too long: the candidate is skipped this round, not blamed.
     */
    private suspend fun checkCandidate(outboundId: String, outboundJson: String): Result<Long>? {
        val key = HandshakeKey.fromOutboundJson(outboundJson)?.also { outboundKeys[outboundId] = it }
        val tag = standbyTags[outboundId]?.takeIf { engineReady }
        if (key != null) {
            val wait = handshakeBudget.waitMs(key, clock())
            if (wait > CANDIDATE_BUDGET_WAIT_MS) {
                LerNetLog.i(TAG, "candidate $outboundId skipped: handshake budget busy for ${wait}ms")
                return null
            }
            if (wait > 0L) delay(wait)
            handshakeBudget.record(key, clock())
        }
        if (tag != null) {
            val l7 = try {
                engine.probeOutbound(tag, L7_PROBE_URL, CANDIDATE_CHECK_TIMEOUT_MS)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Result.failure(error)
            }
            LerNetLog.i(TAG, "candidate $outboundId check via $tag ok=${l7.isSuccess}")
            return l7.map { it.toLong() }
        }
        val endpoint = OutboundEndpoint.parse(outboundJson) ?: return null
        val dial = try {
            outboundDialer.dial(endpoint, CANDIDATE_CHECK_TIMEOUT_MS)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            LerNetLog.w(TAG, "failover candidate probe failed: ${error.message}", error)
            Result.failure(error)
        }
        LerNetLog.i(TAG, "candidate $outboundId check tcp ${endpoint.label()} ok=${dial.isSuccess}")
        return dial.map { 0L }
    }

    private fun candidateFailure(error: Throwable?): String = when (error) {
        is java.net.SocketTimeoutException -> "нет ответа · время истекло"
        is java.net.UnknownHostException -> "адрес сервера не найден"
        is java.net.ConnectException -> "сервер отклонил соединение"
        else -> "сервер не отвечает"
    }

    private suspend fun onStartFailed(error: Throwable, json: String, crumb: String) {
        if (error is CancellationException) throw error
        LerNetLog.e(TAG, "engine.start failed: ${error.message}", error)
        CrashTrail.recordFailure(crumb, error)
        if (!standbyRefused && ConfigAssembler.hasStandby(json)) {
            // A backup the core cannot load must never cost the running config: drop the extras.
            standbyRefused = true
            standbyTags = emptyMap()
            LerNetLog.w(TAG, "core refused the config with standby backups; next start without them")
        }
        dispatch(PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed(error.message ?: "unknown")))
    }

    private fun keyFromAssembled(json: String, tag: String): HandshakeKey? = runCatching {
        kotlinx.serialization.json.Json.parseToJsonElement(json).let { root ->
            (root as kotlinx.serialization.json.JsonObject)["outbounds"]
        }.let { it as? kotlinx.serialization.json.JsonArray }
            ?.firstOrNull { (it as? kotlinx.serialization.json.JsonObject)?.get("tag")?.toString()?.trim('"') == tag }
            ?.let { HandshakeKey.fromOutbound(it as kotlinx.serialization.json.JsonObject) }
    }.getOrNull()

    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        val timeoutMs = context.reconnect.connectTimeoutMs
        connectTimeoutJob = scope.launch {
            delay(timeoutMs)
            val snap = _snapshot.value
            if (snap.state == ConnectionState.CONNECTING || snap.state == ConnectionState.RECONNECTING) {
                dispatch(PolicyEvent.ConnectTimeout)
            }
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    private fun startOutboundProbe() {
        probeJob?.cancel()
        val tag = pendingProxyTag.orEmpty().ifBlank { "proxy" }
        val target = pendingEndpoint
        probeJob = scope.launch {
            CrashTrail.mark("probe after EngineStarted l7Gate=$l7UrlTestEnabled")
            if (target != null) {
                CrashTrail.mark("before TCP probe ${target.label()}")
                val startedAt = System.nanoTime()
                val tcp = runCatching { outboundDialer.dial(target, probeTimeoutMs) }.getOrElse { Result.failure(it) }
                if (tcp.isSuccess) {
                    _snapshot.value = _snapshot.value.copy(
                        serverTcpMs =
                        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                    )
                }
                CrashTrail.mark("after TCP probe ok=${tcp.isSuccess}")
                channel = ChannelWatch.afterProbe(hopUp, tcp.isSuccess)
                hopUp = tcp.isSuccess
                hopCheckedThisWindow = true
                if (tcp.isFailure) {
                    val detail = tcp.exceptionOrNull()?.message ?: "TCP probe failed"
                    LerNetLog.e(TAG, "TCP probe ${target.label()} failed: $detail")
                    val cause = if (VpnGuard.isRevokeMessage(detail)) {
                        ConnectionCause.ServiceRevoked
                    } else {
                        ConnectionCause.OutboundUnreachable("TCP ${target.label()}: $detail")
                    }
                    dispatch(PolicyEvent.EngineFailed(cause))
                    return@launch
                }
                LerNetLog.i(TAG, "TCP probe ${target.label()} ok")
                sealSilentPathIfDialOk()
            } else {
                CrashTrail.mark("TCP probe skipped: no endpoint")
            }
            val snapBefore = _snapshot.value
            if (snapBefore.state != ConnectionState.CONNECTING && snapBefore.state != ConnectionState.RECONNECTING) {
                return@launch
            }
            if (!l7UrlTestEnabled) {
                CrashTrail.mark("L7 initial URLTest disabled; startup readiness uses CommandServer.ready and TCP")
                LerNetLog.i(TAG, "L7 URLTest skipped; Connected = CommandServer.ready + TCP")
                dispatch(PolicyEvent.OutboundReady)
                cancelConnectTimeout()
                armTrafficWatch()
                armDnsHealth()
                armTunnelHealth()
                return@launch
            }
            CrashTrail.mark("L7 URLTestOutbound tag=$tag url=$L7_PROBE_URL")
            LerNetLog.i(TAG, "L7 URLTestOutbound tag=$tag url=$L7_PROBE_URL")
            val l7 = runCatching { engine.probeOutbound(tag, L7_PROBE_URL, probeTimeoutMs) }
                .getOrElse { Result.failure(it) }
            val snap = _snapshot.value
            if (snap.state != ConnectionState.CONNECTING && snap.state != ConnectionState.RECONNECTING) {
                return@launch
            }
            if (l7.isSuccess) {
                LerNetLog.i(TAG, "L7 probe ok tag=$tag delayMs=${l7.getOrNull()}")
                dispatch(PolicyEvent.OutboundReady)
                cancelConnectTimeout()
                armTrafficWatch()
                armDnsHealth()
                armTunnelHealth()
            } else {
                val detail = l7.exceptionOrNull()?.message ?: "L7 probe failed"
                LerNetLog.e(TAG, "L7 probe failed tag=$tag: $detail")
                dispatch(
                    PolicyEvent.EngineFailed(
                        ConnectionCause.OutboundUnreachable("L7 $tag: $detail"),
                    ),
                )
            }
        }
    }

    /**
     * Tunnel check inside the tunnel, only when there is a reason ([TunnelProbeSchedule]): at
     * start, after a network change, or when our traffic stays unanswered. No fixed beat and no
     * retry burst; the probe reuses an open keep-alive connection (see the app's probe).
     */
    /**
     * While connected, check one folder backup at a time at a random 45 s – 5 min pace, also on
     * mobile data. Through the running tunnel when possible, so the local network does not see
     * connections to backup servers. Failover then tries verified backups first.
     */
    private fun armBackupWatch() {
        backupJob?.cancel()
        val probe = backupProbe ?: return
        backupJob = scope.launch {
            while (true) {
                delay(backupDelayMs())
                val snap = _snapshot.value
                if (snap.state != ConnectionState.CONNECTED || !context.failover.enabled) continue
                val group = context.group ?: continue
                val id = backupRotation.next(group.outboundIds, snap.activeOutboundId) ?: continue
                val profile = try {
                    resolveFailoverProfile?.invoke(id)?.first
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                } ?: continue
                val endpoint = profile.selectedOutbound()?.singBoxJson?.let(OutboundEndpoint::parse) ?: continue
                val access = (AppTraffic.route as? AppTraffic.Route.Through)?.access
                // Through the tunnel the check is one more connection via the active server; without
                // the tunnel it is a new connection straight to the backup server. Either way it counts.
                val budgetFree = if (access != null) {
                    takeActiveBudget()
                } else {
                    val key = HandshakeKey.fromOutboundJson(profile.selectedOutbound()?.singBoxJson)
                    key == null || handshakeBudget.tryTake(key, clock())
                }
                if (!budgetFree) {
                    LerNetLog.i(TAG, "backup check ${endpoint.label()} skipped: handshake budget is used up")
                    continue
                }
                val result = try {
                    probe.check(endpoint, access, userAsked = false)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                } ?: continue
                backupHealth[id] = app.lernet.engine.policy.BackupHealth(result.ok, result.latencyMs, clock(), result.throughTunnel)
                LerNetLog.i(TAG, "backup check ${endpoint.label()} ok=${result.ok} tunnel=${result.throughTunnel}")
            }
        }
    }

    private fun armTunnelHealth() {
        tunnelHealthJob?.cancel()
        armBackupWatch()
        val probe = tunnelHealthProbe ?: run {
            probeSchedule = null
            return
        }
        val profileId = _snapshot.value.activeProfileId
        val outboundId = _snapshot.value.activeOutboundId
        val schedule = tunnelProbeSchedule()
        schedule.onStart(healthClockMs)
        probeSchedule = schedule
        tunnelHealthJob = scope.launch {
            while (true) {
                delay(HEALTH_TICK_MS)
                healthClockMs += HEALTH_TICK_MS
                val before = _snapshot.value
                if (before.state != ConnectionState.CONNECTED ||
                    before.activeProfileId != profileId ||
                    before.activeOutboundId != outboundId
                ) {
                    return@launch
                }
                val reason = synchronized(schedule) { schedule.takeDue(healthClockMs) } ?: continue
                // A check opens a connection through the active server: it must fit the handshake budget.
                if (!takeActiveBudget()) {
                    val wait = activeBudgetWaitMs().coerceAtLeast(HEALTH_TICK_MS)
                    LerNetLog.i(TAG, "tunnel check reason=$reason deferred ${wait}ms: handshake budget")
                    synchronized(schedule) { schedule.defer(reason, healthClockMs + wait) }
                    continue
                }
                val result = try {
                    probe()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Result.failure(error)
                }
                val after = _snapshot.value
                if (after.state != ConnectionState.CONNECTED ||
                    after.activeProfileId != profileId ||
                    after.activeOutboundId != outboundId
                ) {
                    return@launch
                }
                val verdict = synchronized(schedule) { schedule.onResult(healthClockMs, result.isSuccess) }
                when (verdict) {
                    TunnelProbeSchedule.Verdict.HEALTHY -> {
                        LerNetLog.i(TAG, "tunnel check ok reason=$reason")
                        dispatch(PolicyEvent.TunnelVerified)
                    }
                    TunnelProbeSchedule.Verdict.CONFIRM -> {
                        val detail = result.exceptionOrNull()?.message?.take(160) ?: "HTTPS probe failed"
                        LerNetLog.w(TAG, "tunnel check failed (1/2) reason=$reason: $detail")
                    }
                    TunnelProbeSchedule.Verdict.FAILED -> {
                        val detail = result.exceptionOrNull()?.message?.take(160) ?: "HTTPS probe failed"
                        LerNetLog.w(TAG, "tunnel check failed (2/2) reason=$reason: $detail")
                        // The core keeps running: the machine looks for a checked replacement or holds.
                        dispatch(PolicyEvent.EngineFailed(ConnectionCause.TunnelHealthFailed(detail)))
                    }
                }
            }
        }
    }

    private suspend fun noteBlocking(sign: app.lernet.engine.policy.BlockingSign?) {
        sign ?: return
        val state = _snapshot.value.state
        if (state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING &&
            state != ConnectionState.RECONNECTING
        ) {
            return
        }
        LerNetLog.w(TAG, "blocking suspected: $sign")
        dispatch(PolicyEvent.EngineFailed(ConnectionCause.BlockingSuspected(sign)))
    }

    private suspend fun markDnsOk(detail: String) {
        dnsOkInWindow = true
        dnsOkSession = true
        dnsHealth.onDnsOk()
        LerNetLog.i(TAG, detail)
        dispatch(PolicyEvent.DnsPathOk)
    }

    private fun stampedConfigJson(): String {
        val compiled = machineState.snapshot.compiledJson.orEmpty()
        val raw = if (standbyRefused) ConfigAssembler.withoutStandby(compiled) else compiled
        val stamped = DnsBlock.enforceCompiledRemote(raw, pendingProxyTag ?: "proxy", pendingDnsPolicy)
        stamped.notes.filter { note ->
            note.contains(DnsBlock.UNDERLAY_CRUMB_PREFIX) || note.startsWith(DnsBlock.PROFILE_KEPT_PREFIX)
        }.forEach { note ->
            LerNetLog.i(TAG, note)
        }
        return stamped.json
    }

    private fun armDnsHealth() {
        dnsHealthJob?.cancel()
        dnsHealthJob = scope.launch {
            while (true) {
                delay(dnsHealth.deadlineMs)
                if (_snapshot.value.state != ConnectionState.CONNECTED) {
                    return@launch
                }
                val phase = dnsHealth.phase
                if (dnsHealth.onDeadline() == DnsHealthAction.Hold) continue
                // Never a restart. Only real DNS failures after a working start ask for a tunnel check;
                // no DNS at all (idle phone, screen off) proves nothing.
                if (phase != app.lernet.engine.policy.DnsHealthPhase.RecoveringFromFail) continue
                CrashTrail.mark("dns health hint phase=$phase")
                LerNetLog.w(TAG, "dns health: dns failed and no dns query ok for ${dnsHealth.deadlineMs}ms: asking for a tunnel check")
                dispatch(PolicyEvent.DnsHealthTimeout)
            }
        }
    }

    private fun armTrafficWatch() {
        healthJob?.cancel()
        healthJob = scope.launch {
            while (true) {
                val timeoutMs = context.reconnect.watchdogWindowMs(watchdogMisses)
                delay(timeoutMs)
                val snap = _snapshot.value
                if (snap.state != ConnectionState.CONNECTED) {
                    return@launch
                }
                if (!settleQuietWindow(timeoutMs)) return@launch
            }
        }
    }

    fun simulatePipeSilent() {
        channel = ChannelHealth.PIPE_SILENT
        _snapshot.value = _snapshot.value.copy(
            state = ConnectionState.CONNECTED,
            channel = channel,
            dnsOk = true,
        )
        LerNetLog.w(TAG, "debug: simulate PIPE_SILENT (requests into pipe without reply)")
    }

    private fun resetConnectMarkersLocked() {
        tunnelHealthJob?.cancel()
        probeSchedule = null
        blockingDetector.reset()
        loggedTunBytes = false
        dnsOkInWindow = false
        dnsOkSession = false
        dnsHealth.resetForConnect()
        byteGrowth.reset()
        rateSampler.reset()
        watchdogMisses = 0
        statusTicks = 0
        channel = ChannelHealth.UNKNOWN
        shownHops = emptyList()
        shownHopHost = ""
        shownHopTimedOut = false
        shownHopChecked = false
        shownHopRunning = false
        shownPipeSilent = 0
        shownPipeTunnel = 0
        dropInFlightPath()
        _snapshot.value = _snapshot.value.copy(
            serverTcpMs = null,
            channel = channel,
            pipeSilentCount = 0,
            pipeTunnelCount = 0,
            hopHost = "",
            hopTimedOut = false,
            hopChecked = false,
            hopRunning = false,
            hops = emptyList(),
        )
        hopUp = false
        sawDeadline = false
        hopCheckedThisWindow = false
        lastConnectionsOut = 0
    }

    private suspend fun settleQuietWindow(timeoutMs: Long): Boolean {
        val snap = _snapshot.value
        val grew = byteGrowth.observed(
            uplinkBps = snap.uplinkBps,
            downlinkBps = snap.downlinkBps,
            uplinkTotal = snap.uplinkTotal,
            downlinkTotal = snap.downlinkTotal,
        )
        if (grew) return noteGrowth(snap)
        if (!hopCheckedThisWindow) probeHopOnce()
        val pending =
            sawDeadline ||
                lastConnectionsOut > 0 ||
                liveFeed.rows.value.any { LiveConnFormat.unfinished(it) }
        val verdict = ChannelWatch.afterQuietWindow(
            hopUp = hopUp,
            grew = false,
            sawDeadline = sawDeadline,
            requestsPending = pending,
        )
        if (verdict != null) {
            channel = verdict
            _snapshot.value = _snapshot.value.copy(channel = channel)
        }
        val dnsOk = dnsOkInWindow
        dnsOkInWindow = false
        if (dnsOk) {
            if (pending && hopUp) {
                val tunnel = liveFeed.rows.value.filter { it.via == LiveVia.PROXY }
                val flags = tunnel.map { LiveConnFormat.unfinished(it) }
                channel = ChannelWatch.classifySilence(flags) ?: ChannelHealth.PIPE_SILENT
                val silent = flags.count { it }
                shownPipeSilent = silent
                shownPipeTunnel = flags.size
                _snapshot.value = _snapshot.value.copy(
                    channel = channel,
                    pipeSilentCount = silent,
                    pipeTunnelCount = flags.size,
                )
                LerNetLog.w(
                    TAG,
                    "watchdog: dns ok but tunnel replies missing " +
                        "channel=$channel silent=$silent/${flags.size} " +
                        "(conns=$lastConnectionsOut deadline=$sawDeadline)",
                )
            } else {
                LerNetLog.i(TAG, "watchdog: hold Connected — dns query ok in window, status still 0 B/s")
            }
            return true
        }
        watchdogMisses += 1
        LerNetLog.w(
            TAG,
            "watchdog: no byte growth and no dns-ok for ${timeoutMs}ms " +
                "(miss=$watchdogMisses, pending=$pending, last up=${snap.uplinkTotal} down=${snap.downlinkTotal})",
        )
        // Silence of an idle phone proves nothing; requests left unanswered only ask for a tunnel check.
        if (pending) dispatch(PolicyEvent.WatchdogMiss)
        return true
    }

    private fun noteGrowth(snap: ConnectionSnapshot): Boolean {
        watchdogMisses = 0
        hopCheckedThisWindow = false
        sawDeadline = false
        if (hopUp) channel = ChannelHealth.HOP_UP
        _snapshot.value = _snapshot.value.copy(channel = channel)
        LerNetLog.i(TAG, "watchdog: byte growth up=${snap.uplinkTotal} down=${snap.downlinkTotal}")
        return true
    }

    /** Manual refresh of the Connect path. Not a per-row diagnostics traceroute. */
    fun refreshHop() {
        scheduleTrace(force = true)
    }

    /** Inspect the selected server without starting a VPN session. */
    fun previewHop(endpoint: OutboundEndpoint) {
        scheduleTrace(force = true, targetOverride = endpoint)
    }

    private fun dropInFlightPath() {
        pathJob?.cancel()
        pathJob = null
        pathTraced = false
        pathGeneration.incrementAndGet()
        shownHopRunning = false
    }

    /** Channel health only. The visible path is [scheduleTrace], a sibling of tunnel dial. */
    private suspend fun probeHopOnce() {
        hopCheckedThisWindow = true
        val target = pendingEndpoint ?: return
        val tcp = runCatching { outboundDialer.dial(target, ACTIVE_PROBE_TIMEOUT_MS) }.getOrElse { Result.failure(it) }
        channel = ChannelWatch.afterProbe(hopUp, tcp.isSuccess)
        hopUp = tcp.isSuccess
        _snapshot.value = _snapshot.value.copy(channel = channel)
    }

    /**
     * Background hop map. Launched on [Dispatchers.IO] so ping waits never touch Main.
     * Connect / OutboundReady never join this job.
     */
    private fun scheduleTrace(force: Boolean, targetOverride: OutboundEndpoint? = null) {
        if (!force && pathTraced) return
        val target = targetOverride ?: pendingEndpoint ?: return
        pathTraced = true
        pathJob?.cancel()
        val generation = pathGeneration.incrementAndGet()
        pathJob = scope.launch(pathDispatcher) {
            publishHops(emptyList(), running = true, generation, target.host)
            val stops = pathTracer.traceLive(target, dialOk = { targetOverride == null && hopUp }) { partial ->
                publishHops(partial, running = true, generation, target.host)
            }
            publishHops(stops, running = false, generation, target.host)
        }
    }

    private fun publishHops(stops: List<HopStop>, running: Boolean, generation: Int, host: String) {
        if (generation != pathGeneration.get()) return
        rememberHops(stops, running, host)
    }

    /**
     * If the hop walk finished before TCP while ICMP was silent, attach a synthetic
     * destination once dial succeeds — without restarting the walk or blocking Connect.
     */
    private fun sealSilentPathIfDialOk() {
        if (!hopUp || shownHopRunning) return
        val host = pendingEndpoint?.host ?: return
        if (HopPath.answered(shownHops).isNotEmpty()) return
        if (shownHops.any { it.synthetic && !it.timedOut }) return
        val dest = HopStop(address = host, country = null, timedOut = false, synthetic = true)
        rememberHops(shownHops + dest, running = false)
    }

    private fun rememberHops(stops: List<HopStop>, running: Boolean, host: String? = null) {
        shownHops = stops
        shownHopChecked = stops.isNotEmpty() || running
        shownHopTimedOut = stops.any { it.timedOut }
        shownHopRunning = running
        shownHopHost = host ?: pendingEndpoint?.host
            ?: stops.lastOrNull { !it.address.isNullOrBlank() }?.address.orEmpty()
        _snapshot.value = _snapshot.value.copy(
            hops = shownHops,
            hopChecked = shownHopChecked,
            hopTimedOut = shownHopTimedOut,
            hopRunning = shownHopRunning,
            hopHost = shownHopHost,
        )
    }

    private fun noteTunnelSilence(rows: List<LiveConn>) {
        val tunnel = rows.filter { it.via == LiveVia.PROXY }
        val flags = tunnel.map { LiveConnFormat.unfinished(it) }
        val verdict = ChannelWatch.classifySilence(flags)
        if (verdict != null) {
            channel = verdict
        } else if (tunnel.isNotEmpty() && isSilence(channel)) {
            channel = if (hopUp) ChannelHealth.HOP_UP else ChannelHealth.UNKNOWN
        }
        val silent = flags.count { it }
        shownPipeSilent = silent
        shownPipeTunnel = flags.size
        _snapshot.value = _snapshot.value.copy(
            channel = channel,
            pipeSilentCount = silent,
            pipeTunnelCount = flags.size,
        )
    }

    private fun isSilence(health: ChannelHealth): Boolean =
        health == ChannelHealth.PIPE_SILENT || health == ChannelHealth.TUNNEL_DEAD

    private fun List<RuleNodeRecord>.toRouting(): List<RuleNode> = map { it.toRuleNode() }

    companion object {
        const val HARD_STOP_TIMEOUT_MS = 2_500L
        const val PROBE_TIMEOUT_MS = 30_000
        private const val ACTIVE_PROBE_TIMEOUT_MS = 8_000
        private const val UNDERLAY_RETURN_RETRY_MS = 1_000L
        const val L7_PROBE_URL = "https://cp.cloudflare.com/generate_204"
        /** One real check of a folder config: a jammed handshake takes 5–20 s ([TunnelProbeSchedule.CHECK_TIMEOUT_MS]). */
        const val CANDIDATE_CHECK_TIMEOUT_MS = 15_000
        private const val CANDIDATE_BUDGET_WAIT_MS = app.lernet.engine.policy.LinkJudge.CANDIDATE_BUDGET_WAIT_MS
        private const val REASON_RESUME = "resume"
        const val DEFAULT_L7_URL_TEST_ENABLED = false
        private const val STATUS_LOG_EVERY = 5
        private const val HEALTH_TICK_MS = 1_000L
        private const val TAG = "LerNet.Engine"
    }

    private fun failedReason(cause: ConnectionCause?): String = when (cause) {
        is ConnectionCause.TunnelHealthFailed -> "сервер отвечает, но трафик через него не идёт"
        is ConnectionCause.BlockingSuspected -> "похоже на блокировку в этой сети"
        is ConnectionCause.DialTimeout, is ConnectionCause.ConnectTimeout -> "нет ответа · время истекло"
        is ConnectionCause.TlsFailure, is ConnectionCause.HandshakeFailure -> "рукопожатие с сервером не прошло"
        is ConnectionCause.ConnectionReset -> "соединение сбрасывается"
        is ConnectionCause.WatchdogTimeout, is ConnectionCause.DnsStalled, is ConnectionCause.DnsUnreachable -> "трафик перестал идти"
        is ConnectionCause.ReconnectExhausted -> failedReason(cause.last)
        else -> "перестал отвечать"
    }

}
