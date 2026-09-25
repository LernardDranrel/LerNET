package app.lernet.engine

import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.Profile
import app.lernet.config.repo.RuleNodeRecord
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
import app.lernet.routing.RuleNode
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ConnectionController(
    private val engine: BoxEngine,
    private val scope: CoroutineScope,
    private val machine: ConnectionPolicyMachine = ConnectionPolicyMachine(),
    private val hardStopTimeoutMs: Long = HARD_STOP_TIMEOUT_MS,
    private val outboundDialer: OutboundDialer = ImmediateSuccessDialer,
    private val probeTimeoutMs: Int = PROBE_TIMEOUT_MS,
    private val l7UrlTestEnabled: Boolean = DEFAULT_L7_URL_TEST_ENABLED,
    val liveFeed: LiveFeed = LiveFeed(),
    private val ruleSetDirectory: String = "",
    /** Hop walk only — never Main. Tests inject the test dispatcher. */
    private val pathDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var machineState = MachineState.idle()
    private var context = MachineContext(ReconnectSettings(), app.lernet.engine.policy.FailoverSettings(), null)
    private var retryJob: Job? = null
    private var healthJob: Job? = null
    private var dnsHealthJob: Job? = null
    private val dnsHealth = DnsHealthGate()
    private val byteGrowth = ByteGrowthGate()
    private val rateSampler = RateSampler()
    private var connectTimeoutJob: Job? = null
    private var probeJob: Job? = null
    private var latencyJob: Job? = null
    private var groupProbeJob: Job? = null
    private var failoverSwitchJob: Job? = null
    private var stopJob: Job? = null
    private var pendingEndpoint: OutboundEndpoint? = null
    private var pendingOutboundId: String? = null
    private var pendingLogLevel: String = "warn"
    private var resolveFailoverProfile: (suspend (String) -> Pair<Profile, List<RuleNodeRecord>>?)? = null
    private var onFailoverSelected: (suspend (String) -> Unit)? = null
    private var pendingProxyTag: String? = null
    private var pendingDnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY
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
        context = MachineContext(
            reconnect = reconnect,
            failover = app.lernet.engine.policy.FailoverSettings(failoverEnabled, group?.id),
            group = group,
        )
    }

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
                dispatch(PolicyEvent.EngineStarted)
                if (_snapshot.value.state == ConnectionState.CONNECTING ||
                    _snapshot.value.state == ConnectionState.RECONNECTING
                ) {
                    startOutboundProbe()
                }
            }
            is EngineEvent.Status -> onStatus(event)
            is EngineEvent.Failed -> dispatch(PolicyEvent.EngineFailed(event.cause))
            is EngineEvent.LogLine -> {
                LerNetLog.i(TAG, event.line)
                SniffRematch.fromLibboxLine(event.line)?.let { LerNetLog.i(TAG, it) }
                when {
                    event.line.contains("dns query ok") -> markDnsOk(event.line)
                    event.line.contains("dns query fail") -> dnsHealth.onDnsFail()
                    event.line.contains("context deadline exceeded") -> sawDeadline = true
                }
            }
            is EngineEvent.DnsAlive -> markDnsOk("dns path alive answers=${event.answers}")
            is EngineEvent.Connections -> {
                liveFeed.apply(event.reset, event.rows)
                noteTunnelSilence(event.rows)
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
        dispatch(
            PolicyEvent.EngineStatus(
                uplinkBps = rate.uplinkBps,
                downlinkBps = rate.downlinkBps,
                uplinkTotal = event.uplinkTotal,
                downlinkTotal = event.downlinkTotal,
                connectionsOut = event.connectionsOut,
                dnsOk = dnsOkSession,
            ),
        )
    }

    suspend fun connect(
        profile: Profile,
        nodes: List<RuleNodeRecord>,
        mode: RunMode,
        logLevel: String = "warn",
        defaults: EngineDefaults = EngineDefaults(),
    ) {
        CrashTrail.mark("controller.connect enter profile=${profile.id} mode=$mode")
        val outbound = profile.selectedOutbound()
        if (outbound == null) {
            dispatch(PolicyEvent.EngineFailed(ConnectionCause.InvalidConfig(listOf("нет выбранного outbound"))))
            return
        }
        val compiled = try {
            RouteCompiler.compile(nodes.toRouting())
        } catch (error: Throwable) {
            CrashTrail.recordFailure("RouteCompiler.compile", error)
            dispatch(PolicyEvent.EngineFailed(ConnectionCause.InvalidRouteTree(listOf(error.message ?: "compile"))))
            return
        }
        val effectiveLog = if (logLevel == "warn" || logLevel == "error") "info" else logLevel
        pendingDnsPolicy = profile.dnsPolicy
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
        pendingEndpoint = OutboundEndpoint.parse(outbound.singBoxJson)
            ?: OutboundEndpoint.fromAssembled(assembled.json, assembled.proxyTag)
        pendingOutboundId = outbound.id
        pendingLogLevel = logLevel
        pendingProxyTag = assembled.proxyTag
        resetConnectMarkers()
        assembled.notes.forEach { LerNetLog.i(TAG, it) }
        CrashTrail.mark("controller.connect profile=${profile.id} mode=$mode l7Gate=$l7UrlTestEnabled")
        LerNetLog.i(TAG, "assembled json: ${assembled.json}")
        LerNetLog.i(TAG, "L7 probe tag=${pendingProxyTag ?: "?"} tcp=${pendingEndpoint?.label() ?: "missing"} log=$effectiveLog")
        dispatch(
            PolicyEvent.StartRequested(
                profileId = profile.id,
                outboundId = outbound.id,
                mode = mode,
                compiledJson = assembled.json,
            ),
        )
    }

    suspend fun disconnect() {
        dispatch(PolicyEvent.UserDisconnect)
    }

    suspend fun onPermissionDenied() {
        dispatch(PolicyEvent.PermissionDenied)
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
        mutex.withLock {
            val (next, commands) = machine.reduce(machineState, context, event)
            machineState = next
            val hops = _snapshot.value
            _snapshot.value = next.snapshot.copy(
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
            commands.forEach { execute(it) }
        }
    }

    private fun execute(command: PolicyCommand) {
        when (command) {
            is PolicyCommand.StartEngine -> {
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
                            connect(target.first, target.second, machineState.snapshot.mode, pendingLogLevel)
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
                scope.launch {
                    runCatching { engine.start(json, mode) }
                        .onFailure { error ->
                            LerNetLog.e(TAG, "engine.start failed: ${error.message}", error)
                            CrashTrail.recordFailure("controller.engine.start", error)
                            dispatch(PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed(error.message ?: "unknown")))
                        }
                }
            }
            is PolicyCommand.StopEngine -> {
                retryJob?.cancel()
                healthJob?.cancel()
                dnsHealthJob?.cancel()
                connectTimeoutJob?.cancel()
                probeJob?.cancel()
                latencyJob?.cancel()
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
                latencyJob?.cancel()
                dnsHealthJob?.cancel()
                connectTimeoutJob?.cancel()
                groupProbeJob?.cancel()
                failoverSwitchJob?.cancel()
                scheduleTrace(force = true)
                retryJob = scope.launch {
                    delay(command.delayMs)
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
                    runCatching { engine.start(json, mode) }
                        .onFailure { error ->
                            LerNetLog.e(TAG, "engine.start retry failed: ${error.message}", error)
                            CrashTrail.recordFailure("controller.engine.retry", error)
                            dispatch(PolicyEvent.EngineFailed(ConnectionCause.EngineStartFailed(error.message ?: "unknown")))
                        }
                }
            }
            is PolicyCommand.ProbeGroup -> {
                connectTimeoutJob?.cancel()
                groupProbeJob?.cancel()
                groupProbeJob = scope.launch {
                    val resolve = resolveFailoverProfile
                    var winner: String? = null
                    if (resolve != null) {
                        for (outboundId in command.outboundIds) {
                            val profile = try {
                                resolve(outboundId)?.first
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                LerNetLog.w(TAG, "failover candidate lookup failed: ${error.message}", error)
                                null
                            } ?: continue
                            val json = profile.selectedOutbound()?.singBoxJson ?: continue
                            val endpoint = OutboundEndpoint.parse(json) ?: continue
                            val alive = try {
                                outboundDialer.dial(endpoint, probeTimeoutMs).isSuccess
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                LerNetLog.w(TAG, "failover candidate probe failed: ${error.message}", error)
                                false
                            }
                            if (alive) {
                                winner = outboundId
                                break
                            }
                        }
                    }
                    onProbeResult(winner?.let(::setOf) ?: emptySet())
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
                CrashTrail.mark("L7 URLTest skipped: gate off (standalone CommandClient.connect SIGSEGV on lx.8)")
                LerNetLog.i(TAG, "L7 URLTest skipped; Connected = CommandServer.ready + TCP")
                dispatch(PolicyEvent.OutboundReady)
                cancelConnectTimeout()
                armTrafficWatch()
                armDnsHealth()
                armLatencyRefresh()
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
                armLatencyRefresh()
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

    private fun armLatencyRefresh() {
        latencyJob?.cancel()
        val target = pendingEndpoint ?: return
        val profileId = _snapshot.value.activeProfileId
        latencyJob = scope.launch {
            while (true) {
                delay(30_000L)
                if (_snapshot.value.state != ConnectionState.CONNECTED ||
                    _snapshot.value.activeProfileId != profileId
                ) {
                    break
                }
                val startedAt = System.nanoTime()
                val result = runCatching { outboundDialer.dial(target, probeTimeoutMs) }
                    .getOrElse { Result.failure(it) }
                if (_snapshot.value.state == ConnectionState.CONNECTED &&
                    _snapshot.value.activeProfileId == profileId
                ) {
                    _snapshot.value = _snapshot.value.copy(
                        serverTcpMs = if (result.isSuccess) {
                            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    private suspend fun markDnsOk(detail: String) {
        dnsOkInWindow = true
        dnsOkSession = true
        dnsHealth.onDnsOk()
        LerNetLog.i(TAG, detail)
        dispatch(PolicyEvent.DnsPathOk)
    }

    private fun stampedConfigJson(): String {
        val raw = machineState.snapshot.compiledJson.orEmpty()
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
                when (val action = dnsHealth.onDeadline()) {
                    DnsHealthAction.Hold -> continue
                    DnsHealthAction.SoftReload,
                    DnsHealthAction.Fail,
                    -> {
                        CrashTrail.mark(
                            "dns health ${action.name} phase=${dnsHealth.phase} " +
                                "reloads=${dnsHealth.reloadsUsed}",
                        )
                        LerNetLog.w(
                            TAG,
                            "dns health: no dns query ok for ${dnsHealth.deadlineMs}ms " +
                                "action=$action phase=${dnsHealth.phase}",
                        )
                        dispatch(PolicyEvent.DnsHealthTimeout)
                        return@launch
                    }
                }
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

    private suspend fun resetConnectMarkers() {
        mutex.withLock { resetConnectMarkersLocked() }
    }

    private fun resetConnectMarkersLocked() {
        latencyJob?.cancel()
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
                "(miss=$watchdogMisses, last up=${snap.uplinkTotal} down=${snap.downlinkTotal})",
        )
        dispatch(PolicyEvent.WatchdogMiss)
        return false
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
        val tcp = runCatching { outboundDialer.dial(target, probeTimeoutMs) }.getOrElse { Result.failure(it) }
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
        const val PROBE_TIMEOUT_MS = 8_000
        const val L7_PROBE_URL = "https://cp.cloudflare.com/generate_204"
        const val DEFAULT_L7_URL_TEST_ENABLED = false
        private const val STATUS_LOG_EVERY = 5
        private const val TAG = "LerNet.Engine"
    }
}
