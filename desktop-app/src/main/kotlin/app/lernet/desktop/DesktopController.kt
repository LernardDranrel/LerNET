package app.lernet.desktop

import app.lernet.config.net.AppTraffic
import app.lernet.config.net.LocalProxyAccess

import app.lernet.desktop.protection.SimpleGuardStatus
import app.lernet.desktop.protection.SimpleTunnelGuard

import app.lernet.config.net.OkHttpTextFetcher
import app.lernet.config.model.DnsPolicy
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.config.parse.GuessedMode
import app.lernet.config.parse.ImportCoordinator
import app.lernet.config.parse.ImportHint
import app.lernet.config.parse.ImportResult
import app.lernet.engine.RunMode
import app.lernet.engine.compile.AssembledConfig
import app.lernet.engine.compile.ConfigAssembler
import app.lernet.engine.compile.EnginePlatform
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.OutboundPatch
import app.lernet.routing.GeoRuleSets
import app.lernet.routing.ConditionBlock
import app.lernet.routing.ConditionCodec
import app.lernet.routing.ConditionKind
import app.lernet.routing.MatchJoin
import app.lernet.routing.RouteAction
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RouteElse
import app.lernet.routing.RoutePlatform
import app.lernet.routing.RuleConditions
import app.lernet.routing.RuleMatch
import app.lernet.routing.RuleNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import app.lernet.engine.policy.RecheckJitter
import app.lernet.engine.policy.BlockingDetector
import app.lernet.engine.policy.BlockingSign
import app.lernet.engine.policy.FlowSample
import app.lernet.engine.policy.ProxiedTraffic
import app.lernet.engine.policy.TunnelProbeSchedule
import app.lernet.engine.policy.LinkDecision
import app.lernet.engine.policy.LinkFailure
import app.lernet.engine.policy.LinkJudge
import app.lernet.engine.policy.BackupHealth
import app.lernet.engine.policy.BackupReach
import app.lernet.engine.policy.BackupRotation
import app.lernet.engine.policy.ReachRoute
import app.lernet.engine.policy.ConfigPenalties
import app.lernet.engine.policy.HandshakeBudget
import app.lernet.engine.policy.HandshakeKey
import app.lernet.engine.policy.LinkHold
import app.lernet.engine.policy.RestartLog
import app.lernet.engine.policy.SilentNetworkBackoff
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class ConnectionError(val message: String, val routeOwnerId: String? = null)

/** The applied Simple launch, rather than a later selection or edited profile. Never serialize this object. */
internal class WindowsSimpleConnection(
    val launch: WindowsLaunchSnapshot,
    val profileId: String,
    val mode: RunMode,
    val desiredSnapshot: StoredState,
    val intentGeneration: Long,
)

internal class WindowsSimpleRestoreLease private constructor(
    val connection: WindowsSimpleConnection,
    private val reservationGeneration: Long,
) {
    fun permitsRestore(generation: Long, saved: StoredState, ownedProcessAlive: Boolean): Boolean =
        generation == reservationGeneration && saved == connection.desiredSnapshot && !ownedProcessAlive

    companion object {
        fun capture(connection: WindowsSimpleConnection?, running: WindowsLaunchSnapshot?, desired: Boolean,
                    generation: Long, reservationGeneration: Long, saved: StoredState): WindowsSimpleRestoreLease? =
            connection?.takeIf {
                desired && running === it.launch && generation == it.intentGeneration && saved == it.desiredSnapshot &&
                    saved.selectedProfileId == it.profileId
            }?.let { WindowsSimpleRestoreLease(it, reservationGeneration) }
    }
}

data class DesktopUiState(
    val saved: StoredState = StoredState(),
    val busy: Boolean = false,
    val message: String = "",
    val connectionError: ConnectionError? = null,
    val networkWarning: String = "",
    val probes: Map<String, ProbeResult> = emptyMap(),
    val healthMessage: String = "",
    val healthFailures: Int = 0,
    val healthVerified: Boolean? = null,
    /** HTTP response time measured by sing-box through the selected outbound. */
    val tunnelLatencyMs: Long? = null,
    /** Shown only when the VPN-mode guard could not close Windows outside LerNET. */
    val guardProblem: String? = null,
    /** Proxy mode: login and password the running local proxy requires. */
    val localProxy: LocalProxyAccess? = null,
    /** Honest failover report: which configs were tried, why they failed and when the next attempt is. */
    val tried: FailoverReport? = null,
    /** The connection is held without a restart: replies are jammed, or no server answers at all. */
    val hold: LinkHold? = null,
)

typealias FailoverAttempt = app.lernet.engine.policy.FailoverAttempt
typealias FailoverReport = app.lernet.engine.policy.FailoverReport

class DesktopController(
    private val store: DesktopStore = DesktopStore(),
    private val simpleGuard: SimpleTunnelGuard = SimpleTunnelGuard(),
) : AutoCloseable {
    @Volatile internal var simpleModeRestriction: (() -> String?)? = null

    private fun simpleRestriction(): String? = try {
        simpleModeRestriction?.invoke()
    } catch (_: Exception) {
        "Не удалось проверить системную защиту. Откройте экспертный режим и проверьте раздел «Защита»."
    }
    init {
        // Raw exception text goes to the journal only; the screen gets UserText sentences.
        app.lernet.engine.text.UserText.logger = { context, error ->
            runCatching { tunnel.logDiagnostic("$context: " + app.lernet.config.redact.SecretRedactor.redact("${error.javaClass.simpleName}: ${error.message.orEmpty()}")) }
        }
    }
    private val loaded = runCatching { store.load() }
    private val mutable = MutableStateFlow(
        DesktopUiState(
            saved = loaded.getOrDefault(StoredState()),
            message = loaded.exceptionOrNull()?.let { app.lernet.engine.text.UserText.withReason("Не удалось прочитать профили", it) }
                ?: if (store.recoveredFromBackup) "Профили восстановлены из резервной копии" else "",
        ),
    )
    val state: StateFlow<DesktopUiState> = mutable
    /** Passive block detector: ordinary traffic and core log lines only, no test downloads. */
    private val blockingDetector = BlockingDetector()
    private val pendingBlockingSign = java.util.concurrent.atomic.AtomicReference<BlockingSign?>(null)

    val tunnel = WindowsBoxProcess(store.workDirectory()).also { process ->
        process.lineListener = { line ->
            synchronized(blockingDetector) { blockingDetector.onLogLine(System.currentTimeMillis(), line) }
                ?.let { pendingBlockingSign.set(it) }
        }
        process.lifecycle = object : TunnelLifecycle {
            override fun coreRunning() {
                if (!simpleGuard.isEngaged()) return
                thread(name = "lernet-windows-guard", isDaemon = true) { showGuard(simpleGuard.permitAdapter()) }
            }

            // Only a terminal failure gives the network back; otherwise it stays closed until the retry or Stop.
            override fun tunnelFailed() {
                if (SimpleRecovery.releasesGuard(process.state.value.failure)) releaseGuard()
            }
        }
    }
    val tracer = WindowsTracer()
    private val coreApi = LocalCoreApi()
    private val mutableDiagnostics = MutableStateFlow(CoreDiagnostics())
    val diagnostics: StateFlow<CoreDiagnostics> = mutableDiagnostics
    private val observedConnections = LinkedHashMap<String, LiveConnection>()
    private val mutableHistory = MutableStateFlow<List<LiveConnection>>(emptyList())
    val connectionHistory: StateFlow<List<LiveConnection>> = mutableHistory
    /** Configs that died rest 2 → 10 → 30 min; kept in the work folder, so the list survives a restart. */
    internal val penalties = ConfigPenalties().also { list ->
        val file = store.workDirectory().resolve(PENALTIES_FILE)
        list.decode(runCatching { Files.readString(file) }.getOrNull(), System.currentTimeMillis())
        list.onChange = { encoded ->
            runCatching { Files.writeString(file, encoded) }
                .onFailure { tunnel.logDiagnostic("Не удалось сохранить список отдыхающих конфигов: ${it.javaClass.simpleName}") }
        }
    }
    /** LerNET's own new connections per server+SNI: at most 3 per minute ([HandshakeBudget]). */
    private val handshakeBudget = HandshakeBudget()
    /** Rounds in a row in which neither the active config nor any folder config answered. */
    private val silentRounds = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var lastRestartAt = 0L
    /** Asks the monitor loop for one tunnel check (within its cooldown). */
    private val hintRequest = AtomicBoolean(false)
    /** Asks the monitor loop to hold and look again after this many ms; -1 = none. */
    private val holdRequest = AtomicLong(-1L)
    private val failoverRunner = FailoverRunner()
    @Volatile private var desiredConnection = false
    private val expertReserved = AtomicBoolean(false)
    private var activeSimpleConnection: WindowsSimpleConnection? = null
    private var simpleRestoreLease: WindowsSimpleRestoreLease? = null
    @Volatile private var monitorOpen = true
    /** When the physical network last changed; a fresh network gets a few seconds to settle. */
    @Volatile private var networkChangedAt = 0L
    private val backupRotation = BackupRotation()
    /** What background checks learned about folder backups; valid for the current network only. */
    private val backupHealth = ConcurrentHashMap<String, BackupHealth>()
    @Volatile private var nextBackupAt = 0L
    private val backupChecking = AtomicBoolean(false)
    private val monitorThread = thread(name = "lernet-windows-diagnostics", isDaemon = true) {
        var lastStatus = TunnelStatus.STOPPED
        // Checks only with a reason (start, network change, unanswered traffic); no fixed beat.
        var schedule = TunnelProbeSchedule()
        val traffic = ProxiedTraffic()
        var failedChecks = 0
        var lastNetwork = runCatching { WindowsTunnelHealth.networkSignature() }.getOrDefault("")
        var lastNetworkScanAt = 0L
        while (monitorOpen) {
            val status = tunnel.state.value.status
            if (status == TunnelStatus.RUNNING) {
                mutableDiagnostics.value = runCatching { coreApi.poll() }
                    .getOrElse { previous -> CoreDiagnostics(error = previous.message ?: "Нет данных от ядра") }
                recordConnections(mutableDiagnostics.value.connections)
                val activeTag = state.value.saved.let { saved ->
                    saved.profiles.firstOrNull { it.id == saved.selectedProfileId }?.selectedOutbound?.tag
                }
                val flows = mutableDiagnostics.value.connections.map { row ->
                    FlowSample(row.id, activeTag != null && activeTag in row.chains, row.upload, row.download, row.active)
                }
                if (lastStatus != TunnelStatus.RUNNING) {
                    // A started core is no proof the config works: failures reset only on a verified reply.
                    schedule = TunnelProbeSchedule().also { it.onStart(System.currentTimeMillis()) }
                    traffic.clear()
                    synchronized(blockingDetector) { blockingDetector.reset() }
                    failedChecks = 0
                    mutable.update { it.copy(message = "", healthMessage = "Проверяем соединение", healthFailures = 0, healthVerified = null, tunnelLatencyMs = null) }
                }
                val now = System.currentTimeMillis()
                traffic.observe(flows)
                schedule.onTraffic(now, traffic.uplink, traffic.replies)
                val sign = synchronized(blockingDetector) { blockingDetector.onFlows(now, flows) }
                    ?: pendingBlockingSign.getAndSet(null)
                if (sign != null) onBlockingSuspected(sign)
                if (hintRequest.getAndSet(false)) schedule.onHint(now)
                holdRequest.getAndSet(-1L).takeIf { it >= 0L }?.let { schedule.planHold(now, it) }
                if (now - lastNetworkScanAt >= 3_000) {
                    lastNetworkScanAt = now
                    runCatching { WindowsTunnelHealth.networkSignature() }.onSuccess { signature ->
                        if (signature != lastNetwork) {
                            lastNetwork = signature
                            networkChangedAt = now
                            // A new network may revive configs that failed on the old one (their level stays).
                            penalties.onNetworkChanged(now)
                            backupHealth.clear()
                            backupRotation.reset()
                            schedule.onNetworkChanged(now)
                            synchronized(blockingDetector) { blockingDetector.reset() }
                            mutable.update { it.copy(healthMessage = "Сеть изменилась · проверяем VPN", healthVerified = null) }
                        }
                    }
                }
                if (nextBackupAt == 0L) nextBackupAt = now + BackupRotation.nextDelayMs()
                if (now >= nextBackupAt) {
                    nextBackupAt = now + BackupRotation.nextDelayMs()
                    if (backupChecking.compareAndSet(false, true)) {
                        probePool.submit { try { checkOneBackup() } finally { backupChecking.set(false) } }
                    }
                }
                val dueReason = schedule.takeDue(now)
                if (dueReason != null && !takeActiveBudget()) {
                    // A check is one more connection through the active server: it must fit the budget.
                    schedule.defer(dueReason, now + activeBudgetWait().coerceAtLeast(1_200L))
                } else if (dueReason != null) {
                    val saved = state.value.saved
                    val profile = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
                    val tag = profile?.selectedOutbound?.tag
                    if (tag != null) {
                        val ticket = connectGeneration.get()
                        val health = WindowsTunnelHealth.check(saved.healthUrl, effectiveMode(profile), proxyAccess = state.value.localProxy)
                        if (ticket == connectGeneration.get() && tunnel.state.value.status == TunnelStatus.RUNNING) {
                            val verdict = schedule.onResult(System.currentTimeMillis(), health.latencyMs != null && !health.routeConflict)
                            if (health.routeConflict) {
                                failedChecks = 0
                                mutable.update { it.copy(healthMessage = health.error, healthFailures = 2,
                                    healthVerified = false, tunnelLatencyMs = null) }
                            } else if (health.latencyMs != null) {
                                failedChecks = 0
                                onConfigWorks(profile.id)
                                mutable.update { it.copy(healthMessage = "VPN отвечает · ${health.latencyMs} мс", healthFailures = 0,
                                    healthVerified = true, tunnelLatencyMs = health.latencyMs, tried = null) }
                            } else {
                                // One more request through the node: it opens a connection, so it is budgeted too.
                                val nodeAlive = takeActiveBudget() &&
                                    runCatching { coreApi.delay(tag, saved.healthUrl, TunnelProbeSchedule.CHECK_TIMEOUT_MS.toInt()) }.isSuccess
                                if (ticket == connectGeneration.get() && tunnel.state.value.status == TunnelStatus.RUNNING) {
                                    failedChecks = if (verdict == TunnelProbeSchedule.Verdict.FAILED) 2 else 1
                                    val reason = if (nodeAlive) "Узел отвечает, но путь Windows через VPN не работает"
                                        else "Трафик через VPN не проходит"
                                    mutable.update { it.copy(healthMessage = "$reason · ${health.error}",
                                        healthFailures = failedChecks, healthVerified = false, tunnelLatencyMs = null) }
                                    if (failedChecks >= 2 && desiredConnection) {
                                        val failedId = profile.id
                                        val error = health.error
                                        // A live node with a dead Windows path is not proof the config died.
                                        val failure = if (nodeAlive) LinkFailure.ENGINE_ERROR else LinkFailure.TUNNEL_DEAD
                                        // Off the loop: a silent neighbour can take up to a minute to probe.
                                        val failures = failedChecks
                                        failoverRunner.launch { actOnLinkFailure(failedId, failure, failures, "$reason · $error") }
                                        failedChecks = 0
                                    }
                                }
                            }
                        } else {
                            // The connection changed under the check: its result means nothing.
                            schedule.onResult(System.currentTimeMillis(), ok = true)
                        }
                    } else {
                        // Nothing to check without a config: drop this reason.
                        schedule.onResult(System.currentTimeMillis(), ok = true)
                    }
                }
            } else if (mutableDiagnostics.value != CoreDiagnostics()) {
                mutableDiagnostics.value = CoreDiagnostics()
                recordConnections(emptyList())
            }
            if (status == TunnelStatus.FAILED && lastStatus != TunnelStatus.FAILED) {
                val permissionFailure = tunnel.state.value.failure == TunnelFailure.TUN_PERMISSION
                if (permissionFailure) desiredConnection = false
                val failure = tunnel.state.value.failure
                val failMessage = tunnel.state.value.message
                val failedId = state.value.saved.selectedProfileId
                val search = {
                    // Same rule as Android: the core is started again after a pause; the second failure in a
                    // row (or the end of the quick tries) looks for a checked replacement in the folder.
                    val attempt = recoveryAttempt.get() + 1
                    val switch = !permissionFailure && failedId != null && desiredConnection && coreFailureSwitches(failedId, attempt)
                    if (switch) {
                        penalties.fail(failedId!!, System.currentTimeMillis())
                    } else if (failedId != null && attempt > SimpleRecovery.QUICK_ATTEMPTS) {
                        penalties.fail(failedId, System.currentTimeMillis())
                        silentRounds.incrementAndGet()
                    }
                    val swapped = switch && tryFailover(failedId!!)
                    if (switch && !swapped) silentRounds.incrementAndGet()
                    // The old code waited for a "попытки исчерпаны" message nobody produced: a failed tunnel stayed off.
                    if (SimpleRecovery.retries(desiredConnection, failure, swapped)) {
                        scheduleRecovery(failMessage)
                    }
                }
                // Off the loop; if a search is already running, it owns the outcome.
                if (!failoverRunner.launch(search) && SimpleRecovery.retries(desiredConnection, failure, swapped = false)) {
                    scheduleRecovery(failMessage)
                }
            }
            if (status == TunnelStatus.STOPPED && lastStatus != TunnelStatus.STOPPED) {
                mutable.update { it.copy(healthMessage = "", healthFailures = 0, healthVerified = null, tunnelLatencyMs = null, tried = null) }
            }
            lastStatus = status
            updateAppTraffic()
            try { Thread.sleep(1_200) } catch (_: InterruptedException) { break }
        }
    }
    /** Login/password of the internal listener for LerNET's own requests in the running launch. */
    @Volatile private var activeAppProxy: LocalProxyAccess? = null

    /**
     * LerNET's own requests: through the running connection, refused while it is wanted but not
     * running (starting, recovering, waiting for a retry), direct only when it is off.
     */
    private fun updateAppTraffic() {
        val access = activeAppProxy
        AppTraffic.set(when {
            access != null && tunnel.state.value.status == TunnelStatus.RUNNING -> AppTraffic.Route.Through(access)
            desiredConnection -> AppTraffic.Route.Closed
            else -> AppTraffic.Route.Direct
        })
    }

    private val importer = ImportCoordinator(OkHttpTextFetcher())
    private val connectBusy = AtomicBoolean(false)
    private val switchBusy = AtomicBoolean(false)
    private val connectGeneration = AtomicLong()
    private val probeSequence = AtomicLong()
    private val probeTickets = ConcurrentHashMap<String, Long>()
    private val connectLock = Any()
    private val coreInstallLock = Any()
    private val probePool = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "lernet-windows-probe").apply { isDaemon = true }
    }

    private fun recordConnections(active: List<LiveConnection>) {
        val now = System.currentTimeMillis()
        val activeIds = active.mapTo(HashSet()) { it.id }
        observedConnections.replaceAll { id, row -> if (id in activeIds) row else row.copy(active = false) }
        active.forEach { row ->
            if (row.id.isNotBlank()) observedConnections[row.id] = row.copy(lastSeenAt = now)
        }
        while (observedConnections.size > 500) {
            observedConnections.remove(observedConnections.keys.first())
        }
        mutableHistory.value = observedConnections.values.sortedByDescending { it.lastSeenAt }
    }

    fun probe(profileIds: Collection<String>) {
        val saved = state.value.saved
        val profiles = saved.profiles.filter { it.id in profileIds }
        if (profiles.isEmpty()) return
        val tickets = profiles.associate { profile ->
            val ticket = probeSequence.incrementAndGet()
            probeTickets[profile.id] = ticket
            mutable.update { it.copy(probes = it.probes + (profile.id to ProbeResult(
                message = "Ждёт очереди", checking = true,
                tunnelMessage = "Ожидает проверки", serverProtocol = EndpointProbe.protocol(profile),
            ))) }
            profile.id to ticket
        }
        // One by one with a random pause, not a burst of parallel handshakes to every server.
        // With the VPN on, TCP servers are reached through it, so this network sees nothing new.
        probePool.submit {
            profiles.forEachIndexed { index, profile ->
                val ticket = tickets.getValue(profile.id)
                if (probeTickets[profile.id] != ticket) return@forEachIndexed
                if (index > 0) runCatching { Thread.sleep(BackupRotation.buttonGapMs()) }
                val running = tunnel.state.value.status == TunnelStatus.RUNNING
                val active = running && profile.id == state.value.saved.selectedProfileId
                val access = (AppTraffic.route as? AppTraffic.Route.Through)?.access?.takeIf { running }
                mutable.update { it.copy(probes = it.probes + (profile.id to ProbeResult(
                    message = "Проверяем ${EndpointProbe.protocol(profile)}", checking = true,
                    tunnelMessage = "Ожидает проверки", serverProtocol = EndpointProbe.protocol(profile),
                ))) }
                if (active) {
                    // The running config is already connected: report the live tunnel check, open nothing new.
                    val ui = state.value
                    if (probeTickets[profile.id] != ticket) return@forEachIndexed
                    mutable.update { it.copy(probes = it.probes + (profile.id to ProbeResult(
                        latencyMs = ui.tunnelLatencyMs, message = "Подключён сейчас",
                        tunnelLatencyMs = ui.tunnelLatencyMs, tunnelMessage = ui.healthMessage.ifBlank { "Работает" },
                        serverProtocol = EndpointProbe.protocol(profile),
                    ))) }
                    return@forEachIndexed
                }
                when (BackupReach.route(access != null, EndpointProbe.isTcp(profile), userAsked = true)) {
                    ReachRoute.TUNNEL -> {
                        // Through the tunnel every check is one more connection via the active server.
                        if (!awaitBudgetShowing(profile.id, ticket, null)) return@forEachIndexed
                        val result = EndpointProbe.checkThroughTunnel(profile, access!!)
                        if (probeTickets[profile.id] != ticket) return@forEachIndexed
                        // A full-path check would start a second core that dials the server from this
                        // network; with the VPN on we do not do that. It happens on switching.
                        mutable.update { it.copy(probes = it.probes + (profile.id to result.copy(
                            tunnelMessage = "Полный путь проверим при переключении",
                        ))) }
                    }
                    else -> {
                        // A direct handshake to this server is one more connection: it counts against its budget.
                        val key = keyOf(profile)
                        if (key != null && !awaitBudgetShowing(profile.id, ticket, key)) return@forEachIndexed
                        val tcp = EndpointProbe.check(profile)
                        if (probeTickets[profile.id] != ticket) return@forEachIndexed
                        mutable.update { it.copy(probes = it.probes + (profile.id to tcp.copy(
                            checking = true, tunnelMessage = "Проверяем канал через узел…",
                        ))) }
                        // The second core opens a connection to this server: it counts against its budget.
                        if (key != null && !awaitBudgetShowing(profile.id, ticket, key)) return@forEachIndexed
                        val outbound = checkOutbound(profile, saved)
                        if (probeTickets[profile.id] != ticket) return@forEachIndexed
                        mutable.update { it.copy(probes = it.probes + (profile.id to tcp.copy(
                            tunnelLatencyMs = outbound.latencyMs,
                            tunnelMessage = outbound.message,
                        ))) }
                    }
                }
            }
        }
    }

    private fun checkOutbound(profile: StoredProfile, saved: StoredState): OutboundProbeResult {
        observeEndpointRoute(profile)
        val result = runCatching {
            val executable = saved.corePath.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: synchronized(coreInstallLock) { BundledCore.install(store.workDirectory()) }
            OutboundProbe.check(profile, saved, executable, store.workDirectory())
        }.getOrElse { OutboundProbeResult(message = app.lernet.engine.text.UserText.of(it, "Не удалось проверить связь через VPN")) }
        if (result.latencyMs == null) {
            tunnel.logDiagnostic("Профиль «${profile.name}»: ${result.message}")
            result.diagnostics.forEach(tunnel::logDiagnostic)
        }
        return result
    }

    private fun observeEndpointRoute(profile: StoredProfile?) {
        val endpoint = runCatching { profile?.selectedOutbound?.singBoxJson?.let { OutboundPatch.read(it).server } }.getOrNull().orEmpty()
        val route = EndpointRouteObservation.read(endpoint)
        if (route.detail.isNotBlank()) tunnel.logDiagnostic(route.detail)
        mutable.update { current ->
            if (current.saved.selectedProfileId == profile?.id) current.copy(networkWarning = route.warning) else current
        }
    }

    fun exportBundle(groupId: String? = null): String = DesktopTransfer.export(state.value.saved, groupId)

    fun exportTo(path: Path, groupId: String? = null) {
        mutable.update { it.copy(busy = true, message = "Создаём архив LerNET") }
        thread(name = "lernet-windows-export", isDaemon = true) {
            runCatching {
                val raw = exportBundle(groupId)
                val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
                Files.writeString(temp, raw)
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }.onSuccess { mutable.update { it.copy(busy = false, message = "Архив сохранён: $path") } }
                .onFailure { error -> mutable.update { it.copy(busy = false, message = app.lernet.engine.text.UserText.withReason("Экспорт не выполнен", error)) } }
        }
    }

    fun importFrom(path: Path) {
        thread(name = "lernet-windows-read-transfer", isDaemon = true) {
            runCatching {
                check(Files.size(path) <= 20_000_000) { "Архив больше 20 МБ" }
                Files.readString(path)
            }.onSuccess(::importBundle)
                .onFailure { error -> mutable.update { it.copy(message = app.lernet.engine.text.UserText.withReason("Не удалось прочитать архив", error)) } }
        }
    }

    fun importBundle(raw: String) {
        mutable.update { it.copy(busy = true, message = "Импортируем архив LerNET") }
        thread(name = "lernet-windows-transfer", isDaemon = true) {
            runCatching {
                val bundle = app.lernet.config.transfer.TransferCodec.decode(raw)
                check(change { DesktopTransfer.merge(it, raw) }) { "Не удалось сохранить импортированные данные" }
                "Добавлено папок: ${bundle.groups.size}, профилей: ${bundle.profiles.size}, правил: ${bundle.rules.size}"
            }.onSuccess { summary -> mutable.update { it.copy(busy = false, message = summary) } }
                .onFailure { error -> mutable.update { it.copy(busy = false, message = app.lernet.engine.text.UserText.withReason("Импорт не выполнен", error)) } }
        }
    }

    fun traceSelected() {
        val profile = state.value.saved.profiles.firstOrNull { it.id == state.value.saved.selectedProfileId } ?: return
        val server = profile.selectedOutbound?.singBoxJson?.let { OutboundPatch.read(it).server }.orEmpty()
        if (server.isNotBlank()) tracer.trace(server)
    }

    fun import(text: String, groupId: String? = null) {
        if (text.isBlank()) return
        if (loaded.isFailure) {
            mutable.update { it.copy(message = "Файл профилей повреждён. Импорт не сохранён.") }
            return
        }
        if (app.lernet.config.transfer.TransferCodec.isTransfer(text)) {
            importBundle(text)
            return
        }
        mutable.update { it.copy(busy = true, message = "Загружаем конфигурацию") }
        thread(name = "lernet-windows-import", isDaemon = true) {
            val result = runCatching {
                val guess = ImportHint.detect(text)
                when (guess.mode) {
                    GuessedMode.VLESS -> importer.importVless(text)
                    GuessedMode.JSON_PASTE -> importer.importPastedJson(text)
                    GuessedMode.JSON_URL -> importer.importJsonUrl(text)
                    GuessedMode.SUBSCRIPTION -> if (guess.document) importer.importSubscriptionDocument(text) else importer.importSubscription(text)
                    null -> if (ImportHint.isHttpUrl(text)) {
                        val classified = importer.classifyUrl(text)
                        if (classified.mode == GuessedMode.JSON_URL) importer.importJsonUrl(text)
                        else importer.importSubscription(text)
                    } else importer.importSubscriptionDocument(text)
                }
            }.getOrElse { ImportResult.Failure(listOf(app.lernet.config.parse.FieldError("import", app.lernet.engine.text.UserText.of(it, "Не удалось прочитать конфиг")))) }
            when (result) {
                is ImportResult.Success -> change { saved ->
                    val added = result.drafts.map { StoredProfile.fromDraft(it).copy(groupId = groupId) }
                    saved.copy(
                        profiles = saved.profiles + added,
                        selectedProfileId = saved.selectedProfileId ?: added.firstOrNull()?.id,
                    )
                }.also { mutable.update { it.copy(busy = false, message = importMessage(result.drafts.size, result.unusedParts)) } }
                is ImportResult.Failure -> mutable.update {
                    it.copy(busy = false, message = result.errors.joinToString("; ") { error -> error.message })
                }
            }
        }
    }

    fun select(profileId: String) {
        if (state.value.saved.selectedProfileId == profileId || state.value.saved.profiles.none { it.id == profileId }) return
        reconfigureConnection { saved -> saved.copy(selectedProfileId = profileId) }
    }

    fun switchMode(profile: StoredProfile?, mode: RunMode) {
        reconfigureConnection { saved ->
            saved.withMode(profile, mode)
        }
    }

    /** Persist the requested VPN mode before UAC starts a second process. */
    fun prepareVpnElevation(profile: StoredProfile?): Boolean = synchronized(connectLock) {
        connectGeneration.incrementAndGet()
        change { it.withMode(profile, RunMode.FULL_VPN) }
    }

    private fun StoredState.withMode(profile: StoredProfile?, mode: RunMode): StoredState =
        if (profile?.modeOverride != null) copy(profiles = profiles.map {
            if (it.id == profile.id) it.copy(modeOverride = mode.name) else it
        }) else copy(mode = mode.name)

    fun clearModeOverride(profileId: String) {
        reconfigureConnection { saved -> saved.copy(profiles = saved.profiles.map {
            if (it.id == profileId) it.copy(modeOverride = null) else it
        }) }
    }

    private fun reconfigureConnection(update: (StoredState) -> StoredState) {
        if (!switchBusy.compareAndSet(false, true)) return
        val restart = synchronized(connectLock) {
            connectGeneration.incrementAndGet()
            !expertReserved.get() && tunnel.state.value.status in
                setOf(TunnelStatus.STARTING, TunnelStatus.RUNNING, TunnelStatus.RECONNECTING)
        }
        if (!restart) {
            try { synchronized(connectLock) { change(update) } } finally { switchBusy.set(false) }
            return
        }
        mutable.update { it.copy(busy = true, message = "Переключаем подключение") }
        thread(name = "lernet-windows-switch", isDaemon = true) {
            try {
                synchronized(connectLock) {
                    desiredConnection = false
                    connectGeneration.incrementAndGet()
                    check(tunnel.stop(waitForExit = true)) { "Не удалось остановить предыдущее подключение" }
                }
                change(update)
                connectInternal(resetFailover = true)
            } catch (error: Exception) {
                desiredConnection = false
                releaseGuard()
                reportConnectionError(app.lernet.engine.text.UserText.of(error, "Не удалось переключить подключение"))
            } finally {
                switchBusy.set(false)
                if (!connectBusy.get()) mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun rename(profileId: String, name: String) = change { saved ->
        saved.copy(profiles = saved.profiles.map { if (it.id == profileId) it.copy(name = name.trim()) else it })
    }

    fun moveProfile(profileId: String, delta: Int) = change { saved ->
        val profile = saved.profiles.firstOrNull { it.id == profileId } ?: return@change saved
        val indexes = saved.profiles.indices.filter { saved.profiles[it].groupId == profile.groupId }
        val current = indexes.indexOfFirst { saved.profiles[it].id == profileId }
        val target = current + delta
        if (current < 0 || target !in indexes.indices) return@change saved
        val list = saved.profiles.toMutableList()
        val from = indexes[current]
        val to = indexes[target]
        list[from] = saved.profiles[to]
        list[to] = saved.profiles[from]
        saved.copy(profiles = list)
    }

    fun dropProfile(profileId: String, targetProfileId: String?, targetGroupId: String?, afterTarget: Boolean) = change { saved ->
        val source = saved.profiles.firstOrNull { it.id == profileId } ?: return@change saved
        val target = saved.profiles.firstOrNull { it.id == targetProfileId && it.id != profileId }
        if (target == null && targetGroupId != null && saved.groups.none { it.id == targetGroupId }) return@change saved
        val groupId = target?.groupId ?: targetGroupId
        val reordered = saved.profiles.filterNot { it.id == profileId }.toMutableList()
        val targetIndex = if (target != null) {
            reordered.indexOfFirst { it.id == target.id }.let { it + if (afterTarget) 1 else 0 }
        } else {
            reordered.indexOfLast { it.groupId == groupId }.let { if (it < 0) reordered.size else it + 1 }
        }
        reordered.add(targetIndex.coerceIn(0, reordered.size), source.copy(groupId = groupId))
        saved.copy(profiles = reordered)
    }

    fun duplicateProfile(profileId: String) = change { saved ->
        val source = saved.profiles.firstOrNull { it.id == profileId } ?: return@change saved
        val copyId = UUID.randomUUID().toString()
        val originalRules = saved.rules.filter { it.profileId == profileId }
        val ids = originalRules.associate { it.id to UUID.randomUUID().toString() }
        val copiedRules = originalRules.map { rule ->
            rule.copy(id = ids.getValue(rule.id), profileId = copyId,
                parentId = app.lernet.config.transfer.TransferCodec.remapParentId(rule.parentId, ids))
        }
        saved.copy(
            profiles = saved.profiles + source.copy(id = copyId, name = source.name + " (копия)",
                canvasLayout = app.lernet.config.transfer.TransferCodec.remapCanvasLayout(source.canvasLayout, ids)),
            rules = saved.rules + copiedRules,
            rulePositions = saved.rulePositions + ids.mapNotNull { (old, fresh) ->
                saved.rulePositions[old]?.let { fresh to it }
            }.toMap(),
        )
    }

    fun delete(profileId: String) {
        if (state.value.saved.selectedProfileId == profileId) disconnect()
        change { saved ->
            val remaining = saved.profiles.filterNot { it.id == profileId }
            val removedIds = saved.rules.filter { it.profileId == profileId }.mapTo(HashSet()) { it.id }
            saved.copy(
                profiles = remaining,
                rules = saved.rules.filterNot { it.profileId == profileId },
                rulePositions = saved.rulePositions.filterKeys { it !in removedIds },
                selectedProfileId = if (saved.selectedProfileId == profileId) remaining.firstOrNull()?.id else saved.selectedProfileId,
            )
        }
    }

    fun setCorePath(path: String) = change { it.copy(corePath = path.trim()) }
    fun setMode(mode: RunMode) = switchMode(null, mode)
    fun effectiveMode(profile: StoredProfile? = null): RunMode = DesktopRunMode.effective(
        profile?.modeOverride ?: state.value.saved.mode, WindowsElevation.isElevated,
    )
    fun showMessage(message: String) { mutable.update { it.copy(message = message) } }
    fun dismissConnectionError() { mutable.update { it.copy(connectionError = null) } }
    fun setHealthUrl(url: String) = change { it.copy(healthUrl = url.trim()) }
    fun setStrictRoute(enabled: Boolean) = change { it.copy(strictRoute = enabled) }

    /** Empty pair returns to random credentials; applies on the next connection. */
    fun setProxyCredentials(username: String, password: String): Boolean {
        val user = username.trim()
        if (user.isEmpty() && password.isEmpty()) return change { it.copy(proxyUsername = "", proxyPassword = "") }
        if (!LocalProxyAccess.valid(user) || !LocalProxyAccess.valid(password)) return false
        return change { it.copy(proxyUsername = user, proxyPassword = password) }
    }
    fun setCrashGuard(enabled: Boolean) = change { it.copy(crashGuard = enabled) }
    fun setDefaultDnsPolicy(policy: DnsPolicy) = change { it.copy(defaultDnsPolicy = policy.name) }
    fun setTlsHandshake(recordFragment: Boolean, keyShare: app.lernet.engine.compile.RealityKeyShare) =
        change { it.copy(tlsRecordFragment = recordFragment, realityKeyShare = keyShare.storage) }
    fun setJournalMaxMb(value: Int) = change { it.copy(journalMaxMb = value.coerceIn(1, 500)) }
    fun setDefaults(mtu: Int, dnsServer: String, logLevel: String) = change {
        it.copy(tunMtu = mtu.coerceIn(1280, 9000), directDnsServer = dnsServer.trim(), logLevel = logLevel)
    }
    /** «Один канал»: XHTTP carries everything over one connection instead of two. */
    fun setXmuxSingleChannel(enabled: Boolean) = change {
        it.copy(xmuxChannels = if (enabled) app.lernet.engine.compile.XhttpMode.SINGLE_CHANNEL else app.lernet.engine.compile.XhttpMode.DEFAULT_CHANNELS)
    }
    fun updateProfile(profile: StoredProfile) = change { saved ->
        saved.copy(profiles = saved.profiles.map { if (it.id == profile.id) profile else it })
    }
    fun addGroup(name: String) = change {
        it.copy(groups = it.groups + StoredGroup(UUID.randomUUID().toString(), name.trim()))
    }
    fun setGroup(profileId: String, groupId: String?) = dropProfile(profileId, null, groupId, false)
    fun reorderGroup(groupId: String, targetId: String, after: Boolean) = change { saved ->
        val source = saved.groups.firstOrNull { it.id == groupId } ?: return@change saved
        val target = saved.groups.firstOrNull { it.id == targetId && it.id != groupId } ?: return@change saved
        val reordered = saved.groups.filterNot { it.id == source.id }.toMutableList()
        val index = reordered.indexOfFirst { it.id == target.id }
        reordered.add((index + if (after) 1 else 0).coerceIn(0, reordered.size), source)
        saved.copy(groups = reordered)
    }
    fun setGroupSwap(groupId: String, enabled: Boolean) = change { saved ->
        saved.copy(groups = saved.groups.map { if (it.id == groupId) it.copy(autoSwap = enabled) else it })
    }
    fun renameGroup(groupId: String, name: String) = change { saved ->
        saved.copy(groups = saved.groups.map { if (it.id == groupId) it.copy(name = name.trim()) else it })
    }
    fun deleteGroup(groupId: String) = change { saved ->
        val removedIds = saved.rules.filter { it.profileId == "grp_$groupId" }.mapTo(HashSet()) { it.id }
        saved.copy(
            groups = saved.groups.filterNot { it.id == groupId },
            profiles = saved.profiles.map { if (it.groupId == groupId) it.copy(groupId = null) else it },
            rules = saved.rules.filterNot { it.id in removedIds },
            rulePositions = saved.rulePositions.filterKeys { it !in removedIds },
        )
    }

    fun saveRule(rule: StoredRule): Boolean = editRules { DesktopRouteTree.save(it, rule) }
    fun deleteRule(ruleId: String): Boolean = editRules { DesktopRouteTree.delete(it, ruleId) }
    fun moveRule(ruleId: String, delta: Int): Boolean = editRules { DesktopRouteTree.move(it, ruleId, delta) }
    fun reorderRule(ruleId: String, targetId: String, after: Boolean): Boolean =
        editRules { DesktopRouteTree.reorder(it, ruleId, targetId, after) }
    data class RuleDraft(val baseline: List<StoredRule>, val rules: List<StoredRule>)
    private val routeDrafts = mutableMapOf<String, RuleDraft>()
    fun routeDraft(ownerId: String): RuleDraft = synchronized(routeDrafts) {
        routeDrafts[ownerId] ?: state.value.saved.rules.filter { it.profileId == ownerId }
            .let { RuleDraft(it, it) }
    }
    fun keepRuleDraft(ownerId: String, baseline: List<StoredRule>, rules: List<StoredRule>) {
        synchronized(routeDrafts) { routeDrafts[ownerId] = RuleDraft(baseline, rules) }
    }
    fun clearRuleDraft(ownerId: String) {
        synchronized(routeDrafts) { routeDrafts.remove(ownerId) }
    }
    fun commitRuleDraft(ownerId: String, baseline: List<StoredRule>, draft: List<StoredRule>): Boolean {
        synchronized(this) {
            val current = state.value.saved.rules.filter { it.profileId == ownerId }
            if (current != baseline) {
                mutable.update { it.copy(message = "Маршруты изменились в другом окне. Черновик не сохранён.") }
                return false
            }
            if (draft.any { it.profileId != ownerId } || draft.map { it.id }.distinct().size != draft.size) {
                mutable.update { it.copy(message = "Черновик содержит некорректные правила.") }
                return false
            }
            return change { saved ->
                val all = saved.rules.filterNot { it.profileId == ownerId } + draft
                saved.copy(rules = all, rulePositions = saved.rulePositions.filterKeys { id -> all.any { it.id == id } })
            }
        }
    }
    fun setRulePosition(ruleId: String, position: RulePosition) = change { saved ->
        saved.copy(rulePositions = saved.rulePositions + (ruleId to position))
    }

    private fun editRules(block: (List<StoredRule>) -> DesktopRouteTree.Edit): Boolean {
        val result = block(state.value.saved.rules)
        if (result.error != null) {
            mutable.update { it.copy(message = result.error) }
            return false
        }
        return change { it.copy(rules = result.rules, rulePositions = it.rulePositions.filterKeys { id -> result.rules.any { rule -> rule.id == id } }) }
    }

    fun preview(profileId: String? = state.value.saved.selectedProfileId, draftProfile: StoredProfile? = null,
                draftRules: List<StoredRule>? = null, draftOwnerId: String? = null): AssembledConfig =
        previewSaved(state.value.saved, profileId, draftProfile, draftRules, draftOwnerId)

    private fun previewSaved(source: StoredState, profileId: String?, draftProfile: StoredProfile? = null,
                             draftRules: List<StoredRule>? = null, draftOwnerId: String? = null,
                             userProxy: LocalProxyAccess? = null, appProxy: LocalProxyAccess? = null): AssembledConfig {
        val saved = if (draftRules != null && draftOwnerId != null) source.copy(
            rules = source.rules.filterNot { it.profileId == draftOwnerId } + draftRules
        ) else source
        val profile = draftProfile ?: saved.profiles.firstOrNull { it.id == profileId }
            ?: return AssembledConfig("", "", listOf("Выберите профиль"))
        val outbound = profile.selectedOutbound
            ?: return AssembledConfig("", "", listOf("В конфиге нет подходящего сервера"))
        val activeRules = routingRules(saved, profile)
        val rules = activeRules.map { rule ->
            val match = ruleMatch(rule)
            RuleNode(
                id = rule.id,
                parentId = rule.parentId,
                enabled = rule.enabled,
                sortIndex = rule.sortIndex,
                match = match,
                action = RouteAction.entries.firstOrNull { it.name == rule.action.uppercase() } ?: RouteAction.PROXY,
                pipeName = rule.pipeName,
                conditions = if (DesktopRouteTree.isElse(rule)) null else if (rule.blocksJson.isNotBlank())
                    ConditionCodec.decode(rule.blocksJson, match)
                else RuleConditions(
                    join = MatchJoin.entries.firstOrNull { it.name == rule.join } ?: MatchJoin.AND,
                    blocks = buildList {
                        if (rule.apps.isNotEmpty()) add(ConditionBlock(ConditionKind.APP, rule.apps))
                        if (rule.domains.isNotEmpty() || rule.domainSuffixes.isNotEmpty()) {
                            add(ConditionBlock(ConditionKind.DOMAIN, rule.domains + rule.domainSuffixes.map { "*.$it" }))
                        }
                        if (rule.cidrs.isNotEmpty()) add(ConditionBlock(ConditionKind.CIDR, rule.cidrs))
                        if (rule.countries.isNotEmpty()) add(ConditionBlock(ConditionKind.GEOIP, rule.countries))
                        if (rule.processes.isNotEmpty()) add(ConditionBlock(ConditionKind.PROCESS, rule.processes))
                    },
                ),
            )
        }.let { nodes ->
            if (nodes.any { RouteElse.isElse(it) && it.parentId == null }) nodes
            else nodes + RuleNode("else-${profile.id}", null, true, Int.MAX_VALUE, RuleMatch(), RouteAction.PROXY)
        }
        val compiled = RouteCompiler.compile(rules, RoutePlatform.WINDOWS)
        val ruleSetDir = store.workDirectory().resolve("rule-set")
        val assembled = ConfigAssembler.assemble(
            outbound = outbound,
            compiledRoute = compiled,
            mode = DesktopRunMode.effective(profile.modeOverride ?: saved.mode, WindowsElevation.isElevated),
            logLevel = saved.logLevel,
            dnsJson = profile.dnsJson,
            dnsPolicy = runCatching {
                DnsPolicy.valueOf(if (profile.dnsPolicy == "SYSTEM") saved.defaultDnsPolicy else profile.dnsPolicy)
            }.getOrDefault(DnsPolicy.DEFAULT),
            ruleSetDirectory = ruleSetDir.toString(),
            defaults = saved.engineDefaults(),
            platform = EnginePlatform.WINDOWS,
            userProxy = userProxy,
            appProxy = appProxy,
        )
        return if (assembled.isValid) assembled.copy(json = coreApi.inject(assembled.json)) else assembled
    }

    private fun ruleMatch(rule: StoredRule) = RuleMatch(
        apps = rule.apps,
        domains = rule.domains,
        domainSuffixes = rule.domainSuffixes,
        ipCidrs = rule.cidrs,
        geoip = rule.countries,
        processes = rule.processes,
    )

    private fun routingRules(saved: StoredState, profile: StoredProfile): List<StoredRule> {
        val groupOwner = profile.groupId?.let { "grp_$it" }
        return saved.rules.filter { it.profileId == (groupOwner ?: profile.id) }
    }

    fun connect() {
        synchronized(connectLock) {
            if (expertReserved.get()) connectGeneration.incrementAndGet()
        }
        connectInternal(resetFailover = true)
    }

    private fun connectInternal(resetFailover: Boolean, holdOnError: Boolean = false) {
        simpleRestriction()?.let { reportConnectionError(it); return }
        if (expertReserved.get()) {
            reportConnectionError("Устройством управляет экспертный режим. Остановите его перед включением обычного VPN.")
            return
        }
        if (loaded.isFailure) {
            reportConnectionError("Файл профилей повреждён. Сохранение отключено до восстановления данных.")
            return
        }
        if (!connectBusy.compareAndSet(false, true)) return
        if (tunnel.state.value.status in setOf(TunnelStatus.STARTING, TunnelStatus.RUNNING, TunnelStatus.RECONNECTING)) {
            connectBusy.set(false)
            return
        }
        if (resetFailover) {
            // The user's own start: a fresh series. Resting configs stay resting (they are not the user's pick).
            recoveryAttempt.set(0)
            silentRounds.set(0)
        }
        val ticket = synchronized(connectLock) {
            if (expertReserved.get()) {
                connectBusy.set(false)
                reportConnectionError("Устройством управляет экспертный режим. Остановите его перед включением обычного VPN.")
                return
            }
            desiredConnection = true
            connectGeneration.incrementAndGet()
        }
        updateAppTraffic()
        val savedAtStart = state.value.saved
        val profileAtStart = savedAtStart.profiles.firstOrNull { it.id == savedAtStart.selectedProfileId }
        mutable.update { it.copy(busy = true, message = "Готовим подключение", connectionError = null) }
        thread(name = "lernet-windows-connect", isDaemon = true) {
            var routeErrorOwner: String? = null
            try {
                // A fresh random login/password for every connection unless the user saved their own.
                val assembled = previewSaved(savedAtStart, profileAtStart?.id,
                    userProxy = LocalProxyAccess.forUserProxy(savedAtStart.proxyUsername, savedAtStart.proxyPassword),
                    appProxy = LocalProxyAccess.random(freeLoopbackPort()))
                if (!assembled.isValid) {
                    routeErrorOwner = profileAtStart?.groupId?.let { "grp_$it" } ?: profileAtStart?.id
                }
                check(assembled.isValid) { assembled.errors.joinToString("; ") }
                assembled.notes.forEach(tunnel::logDiagnostic)
                observeEndpointRoute(profileAtStart)
                traceSelected()
                prepareRuleSets()
                tunnel.journalMaxMb = savedAtStart.journalMaxMb
                val path = savedAtStart.corePath.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: synchronized(coreInstallLock) { BundledCore.install(store.workDirectory()) }
                synchronized(connectLock) {
                    synchronized(this) {
                        if (connectGeneration.get() != ticket || expertReserved.get()) return@thread
                        simpleRestriction()?.let { error(it) }
                        check(state.value.saved == savedAtStart) { "Настройки изменились во время подготовки. Повторите подключение." }
                        check(tunnel.stop(waitForExit = true)) { "Не удалось остановить предыдущее подключение" }
                        val launch = WindowsLaunchSnapshot(path, assembled.json)
                        val profile = checkNotNull(profileAtStart)
                        val runMode = DesktopRunMode.effective(profile.modeOverride ?: savedAtStart.mode, WindowsElevation.isElevated)
                        activeSimpleConnection = WindowsSimpleConnection(launch, profile.id, runMode, savedAtStart, ticket)
                        // Close Windows outside LerNET before the core exists, and keep it closed across restarts.
                        if (runMode == RunMode.FULL_VPN && savedAtStart.crashGuard) {
                            showGuard(simpleGuard.engage(path.toAbsolutePath().toString()))
                        } else {
                            releaseGuard()
                        }
                        mutable.update { it.copy(localProxy = assembled.userProxy.takeIf { runMode == RunMode.PROXY }) }
                        activeAppProxy = assembled.appProxy
                        updateAppTraffic()
                        keyOf(profile)?.let { handshakeBudget.record(it, System.currentTimeMillis()) }
                        lastRestartAt = System.currentTimeMillis()
                        tunnel.start(launch)
                    }
                }
                mutable.update { it.copy(message = "Запускаем VPN") }
            } catch (error: Exception) {
                if (connectGeneration.get() == ticket) {
                    val reason = app.lernet.engine.text.UserText.of(error, "Не удалось запустить VPN")
                    if (holdOnError && desiredConnection && !expertReserved.get()) {
                        // A recovery/failover start failed while the VPN is wanted: keep Windows closed and retry.
                        tunnel.logDiagnostic("Повторный запуск не удался: $reason")
                        scheduleRecovery(reason)
                    } else {
                        desiredConnection = false
                        releaseGuard()
                        reportConnectionError(reason, routeErrorOwner)
                    }
                }
            } finally {
                connectBusy.set(false)
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private val recoveryAttempt = java.util.concurrent.atomic.AtomicInteger()

    /** Retries the wanted connection after a growing random pause; the guard (if on) keeps the network closed meanwhile. */
    private fun scheduleRecovery(reason: String) {
        val ticket = connectGeneration.get()
        val attempt = recoveryAttempt.incrementAndGet()
        val rounds = silentRounds.get()
        val pause = SimpleRecovery.pauseMs(attempt, rounds, activeBudgetWait())
        val tag = if (attempt > SimpleRecovery.QUICK_ATTEMPTS || rounds > 0) "recheck" else "core-failed"
        tunnel.logDiagnostic(RestartLog.line(tag, attempt, pause))
        val hold = if (rounds >= SimpleRecovery.ALL_SILENT_ROUNDS) LinkHold.ALL_SILENT else null
        mutable.update { it.copy(
            message = hold?.let { h -> LinkJudge.holdMessage(h, RecheckJitter.label(pause)) }
                ?: SimpleRecovery.message(simpleGuard.isEngaged(), pause, reason),
            hold = hold,
        ) }
        thread(name = "lernet-windows-retry", isDaemon = true) {
            try { Thread.sleep(pause) } catch (_: InterruptedException) { return@thread }
            if (desiredConnection && !expertReserved.get() && connectGeneration.get() == ticket &&
                tunnel.state.value.status in setOf(TunnelStatus.FAILED, TunnelStatus.STOPPED)) {
                connectInternal(resetFailover = false, holdOnError = true)
            }
        }
    }

    private fun releaseGuard() {
        simpleGuard.release()
        mutable.update { it.copy(guardProblem = null, localProxy = null) }
    }

    private fun showGuard(status: SimpleGuardStatus) {
        guardProblemText(status)?.let { shown ->
            // The technical reason goes only to the journal; the screen gets a plain sentence.
            tunnel.logDiagnostic(SecurityTexts.GUARD_JOURNAL_PREFIX + (status as SimpleGuardStatus.Unavailable).reason)
            mutable.update { it.copy(guardProblem = shown) }
        } ?: mutable.update { it.copy(guardProblem = null) }
    }

    private fun reportConnectionError(message: String, routeOwnerId: String? = null) {
        tunnel.logDiagnostic("Подключение не запущено: $message")
        mutable.update { it.copy(message = message, connectionError = ConnectionError(message, routeOwnerId)) }
    }

    /** The block detector proves nothing: hold the connection and ask for one tunnel check (same as Android). */
    private fun onBlockingSuspected(sign: BlockingSign) {
        tunnel.logDiagnostic(BlockingDetector.message(sign))
        if (!desiredConnection) return
        holdJammed()
        hintRequest.set(true)
    }

    private fun holdJammed() {
        mutable.update {
            if (it.hold == LinkHold.ALL_SILENT) it else it.copy(
                healthMessage = LinkJudge.holdMessage(LinkHold.JAMMED), healthVerified = false, hold = LinkHold.JAMMED,
            )
        }
    }

    /** Replies came back through the config: it works, whatever was suspected before. */
    private fun onConfigWorks(profileId: String) {
        recoveryAttempt.set(0)
        silentRounds.set(0)
        penalties.clear(profileId)
        mutable.update { it.copy(hold = null) }
    }

    private fun keyOf(profile: StoredProfile?): HandshakeKey? =
        profile?.selectedOutbound?.singBoxJson?.let(HandshakeKey::fromOutboundJson)

    private fun activeProfileKey(): HandshakeKey? = state.value.saved.let { saved ->
        keyOf(saved.profiles.firstOrNull { it.id == saved.selectedProfileId })
    }

    private fun takeActiveBudget(): Boolean =
        activeProfileKey()?.let { handshakeBudget.tryTake(it, System.currentTimeMillis()) } ?: true

    /**
     * Waits until the handshake budget allows one more connection ([key]'s server, or the active one
     * when null) and takes it. Meanwhile the check of [profileId] says how long, refreshed every second
     * (see BudgetNotice). False when the check was replaced by a newer one or the wait was interrupted.
     */
    private fun awaitBudgetShowing(profileId: String, ticket: Long, key: HandshakeKey?): Boolean {
        fun show(waitMs: Long?) = mutable.update { ui ->
            val probe = ui.probes[profileId]
            if (probe != null && probeTickets[profileId] == ticket) ui.copy(probes = ui.probes + (profileId to probe.copy(budgetWaitMs = waitMs))) else ui
        }
        while (true) {
            val budgetKey = key ?: activeProfileKey() ?: break
            val now = System.currentTimeMillis()
            if (handshakeBudget.tryTake(budgetKey, now)) break
            if (probeTickets[profileId] != ticket) return false
            val wait = handshakeBudget.waitMs(budgetKey, now)
            show(wait)
            try {
                Thread.sleep(wait.coerceIn(200L, 1_000L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        show(null)
        return true
    }

    private fun activeBudgetWait(): Long =
        activeProfileKey()?.let { handshakeBudget.waitMs(it, System.currentTimeMillis()) } ?: 0L

    /** Folder configs that may be tried now: same folder with auto-switch, not this one, not resting. */
    private fun freeCandidates(saved: StoredState, failedId: String): List<StoredProfile> {
        val failed = saved.profiles.firstOrNull { it.id == failedId } ?: return emptyList()
        if (saved.groups.none { it.id == failed.groupId && it.autoSwap }) return emptyList()
        val now = System.currentTimeMillis()
        return saved.profiles.filter { it.groupId == failed.groupId && it.id != failedId && !penalties.isResting(it.id, now) }
    }

    private fun msSinceNetworkChange(): Long =
        networkChangedAt.let { if (it == 0L) Long.MAX_VALUE else System.currentTimeMillis() - it }

    /** Same rule as Android: a failed core switches on its second failure in a row, or after the quick tries. */
    private fun coreFailureSwitches(failedId: String, attempt: Int): Boolean {
        val hasFree = freeCandidates(state.value.saved, failedId).isNotEmpty()
        if (!hasFree) return false
        if (attempt > SimpleRecovery.QUICK_ATTEMPTS) return true
        val networkUp = runCatching { WindowsPhysicalNetwork.select() }.isSuccess
        return LinkJudge.decide(networkUp, LinkFailure.ENGINE_ERROR, attempt, msSinceNetworkChange(), hasFree) == LinkDecision.SWITCH_NOW
    }

    /** Nothing answered this round while the core runs: hold, count the round, look again later (same as Android). */
    private fun silentRound(failedId: String, detail: String) {
        val rounds = silentRounds.incrementAndGet()
        val hold = if (rounds >= LinkJudge.ALL_SILENT_ROUNDS) LinkHold.ALL_SILENT else LinkHold.JAMMED
        val now = System.currentTimeMillis()
        if (rounds == LinkJudge.STALE_CORE_ROUND && now - lastRestartAt >= LinkJudge.STALE_CORE_GAP_MS && desiredConnection) {
            // A core that kept a broken pool is started once more, at most every 10 min.
            tunnel.logDiagnostic(RestartLog.line("stale-core", rounds, 0L, "profile=$failedId"))
            mutable.update { it.copy(hold = hold, healthMessage = LinkJudge.holdMessage(hold)) }
            synchronized(connectLock) {
                if (!desiredConnection || expertReserved.get()) return
                connectGeneration.incrementAndGet()
                if (!tunnel.stop(waitForExit = true)) return
            }
            connectInternal(resetFailover = false, holdOnError = true)
            return
        }
        val delay = SilentNetworkBackoff.delayMs(rounds)
        tunnel.logDiagnostic("hold: core and live connections kept, next look in ${delay}ms · $detail")
        holdRequest.set(delay)
        mutable.update { it.copy(hold = hold, healthMessage = LinkJudge.holdMessage(hold, RecheckJitter.label(delay))) }
    }


    /**
     * Same rule as Android (shared LinkJudge): no network → keep the tunnel and wait;
     * live network and a dead config → switch at once; otherwise let the next check retry.
     */
    private fun actOnLinkFailure(failedId: String, failure: LinkFailure, failures: Int, detail: String) {
        if (!desiredConnection) return
        val saved = state.value.saved
        val profile = saved.profiles.firstOrNull { it.id == failedId } ?: return
        val hasAlternative = freeCandidates(saved, profile.id).isNotEmpty()
        val networkUp = runCatching { WindowsPhysicalNetwork.select() }.isSuccess
        val since = msSinceNetworkChange()
        when (LinkJudge.decide(networkUp, failure, failures, since, hasAlternative)) {
            LinkDecision.WAIT_FOR_NETWORK ->
                // A failed probe is not evidence that ingress is dead: keep the guard and routes.
                mutable.update { it.copy(healthMessage = LinkJudge.message(LinkDecision.WAIT_FOR_NETWORK)) }
            LinkDecision.HOLD -> if (since in 0 until LinkJudge.SETTLE_MS) {
                // The network appeared a moment ago: nothing is blamed on the config yet.
                holdJammed()
                holdRequest.set(LinkJudge.SETTLE_RECHECK_MS)
            } else {
                penalties.fail(failedId, System.currentTimeMillis())
                silentRound(failedId, detail)
            }
            LinkDecision.SWITCH_NOW -> {
                // The core and its live connections stay until a checked replacement answered.
                penalties.fail(failedId, System.currentTimeMillis())
                holdJammed()
                mutable.update { it.copy(healthMessage = LinkJudge.message(LinkDecision.SWITCH_NOW)) }
                if (!tryFailover(failedId) && desiredConnection) silentRound(failedId, "$detail · другой рабочий конфиг в папке не найден")
            }
            LinkDecision.RETRY_SAME ->
                mutable.update { it.copy(healthMessage = if (hasAlternative) "$detail · ${LinkJudge.message(LinkDecision.RETRY_SAME)}"
                    else "$detail · другого конфига в папке нет, пробуем этот же") }
        }
    }

    /**
     * One folder backup per random 45 s – 5 min, also on mobile data. Through the running tunnel,
     * so this network does not see connections to backup servers; UDP servers are skipped here.
     */
    private fun checkOneBackup() {
        if (tunnel.state.value.status != TunnelStatus.RUNNING) return
        val saved = state.value.saved
        val current = saved.profiles.firstOrNull { it.id == saved.selectedProfileId } ?: return
        if (saved.groups.none { it.id == current.groupId && it.autoSwap }) return
        val members = saved.profiles.filter { it.groupId == current.groupId }.map { it.id }
        val id = backupRotation.next(members, current.id) ?: return
        val profile = saved.profiles.firstOrNull { it.id == id } ?: return
        val access = (AppTraffic.route as? AppTraffic.Route.Through)?.access
        if (BackupReach.route(access != null, EndpointProbe.isTcp(profile), userAsked = false) != ReachRoute.TUNNEL) return
        // Through the tunnel the check is one more connection via the active server.
        if (!takeActiveBudget()) return
        val result = EndpointProbe.checkThroughTunnel(profile, access!!, timeoutMs = 8_000)
        backupHealth[id] = BackupHealth(result.latencyMs != null, result.latencyMs, System.currentTimeMillis(), throughTunnel = true)
    }

    /**
     * Looks for a folder config that passes a real check (a second core makes a request through it), one at a
     * time with a random gap and within each server's handshake budget. Resting configs are skipped; a candidate
     * that did not answer rests. The caller has already marked [failedProfileId] as failed.
     */
    private fun tryFailover(failedProfileId: String): Boolean {
        val saved = state.value.saved
        val failed = saved.profiles.firstOrNull { it.id == failedProfileId } ?: return false
        val group = saved.groups.firstOrNull { it.id == failed.groupId && it.autoSwap } ?: return false
        val unordered = freeCandidates(saved, failedProfileId)
        // Backups verified by the background checks go first: the switch takes seconds.
        val order = BackupHealth.order(unordered.map { it.id }, backupHealth, System.currentTimeMillis())
        val candidates = order.mapNotNull { id -> unordered.firstOrNull { it.id == id } }
        val attempts = mutableListOf(
            FailoverAttempt(failed.name, state.value.probes[failed.id]?.let(::attemptReason) ?: "нет ответа", System.currentTimeMillis()),
        )
        for ((index, candidate) in candidates.withIndex()) {
            // Sequential with a random pause, the same rule as on Android: no burst of handshakes.
            if (index > 0) runCatching { Thread.sleep(LinkJudge.candidateGapMs()) }
            val key = keyOf(candidate)
            if (key != null) {
                val wait = handshakeBudget.waitMs(key, System.currentTimeMillis())
                if (wait > LinkJudge.CANDIDATE_BUDGET_WAIT_MS) {
                    tunnel.logDiagnostic("Профиль «${candidate.name}» пропущен: лимит новых соединений к серверу")
                    continue
                }
                if (wait > 0L) runCatching { Thread.sleep(wait) }
                handshakeBudget.record(key, System.currentTimeMillis())
            }
            val tcp = EndpointProbe.check(candidate)
            val outbound = checkOutbound(candidate, saved)
            val probe = tcp.copy(tunnelLatencyMs = outbound.latencyMs, tunnelMessage = outbound.message)
            mutable.update { it.copy(probes = it.probes + (candidate.id to probe)) }
            if (probe.tunnelLatencyMs != null) {
                synchronized(connectLock) {
                    if (!desiredConnection || expertReserved.get()) return false
                    connectGeneration.incrementAndGet()
                    if (!tunnel.stop(waitForExit = true)) return false
                }
                tunnel.logDiagnostic(RestartLog.line("switch", 0, 0L, "from=${failed.id} to=${candidate.id}"))
                silentRounds.set(0)
                recoveryAttempt.set(0)
                change { it.copy(selectedProfileId = candidate.id) }
                mutable.update { it.copy(message = "Переключаемся на ${candidate.name} из папки «${group.name}»", hold = null) }
                // The guard stays engaged across stop → start; a failed switch keeps it closed and retries.
                connectInternal(resetFailover = false, holdOnError = true)
                return true
            }
            penalties.fail(candidate.id, System.currentTimeMillis())
            attempts += FailoverAttempt(candidate.name, attemptReason(probe), System.currentTimeMillis())
        }
        val now = System.currentTimeMillis()
        val members = saved.profiles.filter { it.groupId == group.id }.map { it.id }
        val wait = (penalties.nextFreeAt(members, now) ?: now) - now
        val label = RecheckJitter.label(wait.coerceAtLeast(RecheckJitter.MIN_MS))
        mutable.update {
            it.copy(
                message = "В папке «${group.name}» нет доступной замены · запасные снова проверим через $label",
                tried = FailoverReport.merge(it.tried, group.name, attempts, label),
            )
        }
        return false
    }

    private fun attemptReason(probe: ProbeResult): String = when {
        probe.latencyMs == null -> probe.message.ifBlank { "сервер не отвечает" }
        else -> probe.tunnelMessage.ifBlank { "сервер отвечает, но трафик через него не идёт" }
    }


    private fun prepareRuleSets() {
        val saved = state.value.saved
        val profile = saved.profiles.firstOrNull { it.id == saved.selectedProfileId } ?: return
        val rules = routingRules(saved, profile)
        val inactive = DesktopRouteTree.platformInactiveIds(rules)
        val tokens = rules.filterNot { it.id in inactive }.flatMap { rule ->
            rule.countries + ConditionCodec.decode(rule.blocksJson, ruleMatch(rule)).blocks
                .filter { it.kind == ConditionKind.GEOIP }.flatMap { it.values }
        }
        val directory = store.workDirectory().resolve("rule-set")
        GeoRuleSets.tags(tokens).forEach { tag ->
            val target = directory.resolve("$tag.srs")
            if (Files.exists(target)) return@forEach
            Files.createDirectories(directory)
            val source = javaClass.getResourceAsStream("/rule-set/$tag.srs")
                ?: error("Нет файла страны: $tag")
            source.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    fun disconnect() {
        synchronized(connectLock) {
            desiredConnection = false
            connectGeneration.incrementAndGet()
            tunnel.stop()
            // Stop means normal connectivity right away, with nothing left blocked.
            releaseGuard()
            activeAppProxy = null
            updateAppTraffic()
        }
        mutable.update { it.copy(message = "Отключено", tried = null) }
    }

    /** Report only a running VPN whose profile/settings still match the applied launch. */
    fun connectedVpnProfileId(): String? = synchronized(connectLock) {
        val connection = activeSimpleConnection ?: return@synchronized null
        connection.profileId.takeIf {
            connection.mode == RunMode.FULL_VPN && desiredConnection &&
                tunnel.captureRunningLaunch() === connection.launch &&
                connectGeneration.get() == connection.intentGeneration && state.value.saved == connection.desiredSnapshot
        }
    }

    /** Cancels pending Simple starts before the Expert host is allowed to acquire TUN. */
    fun reserveExpertMode() {
        synchronized(connectLock) {
            check(expertReserved.compareAndSet(false, true)) { "Экспертный режим уже запускается" }
            val oldGeneration = connectGeneration.get()
            val nextGeneration = connectGeneration.incrementAndGet()
            simpleRestoreLease = WindowsSimpleRestoreLease.capture(activeSimpleConnection, tunnel.captureRunningLaunch(),
                desiredConnection, oldGeneration, nextGeneration, state.value.saved)
            desiredConnection = false
            try {
                check(tunnel.stop(waitForExit = true)) {
                    "Предыдущее подключение ещё работает. Экспертный туннель не запущен."
                }
                releaseGuard()
            } catch (error: Exception) {
                simpleRestoreLease = null
                expertReserved.set(false)
                throw error
            }
        }
    }

    /** Only call after the Expert host confirms shutdown or its owned process has exited. */
    fun releaseExpertMode(restorePrevious: Boolean = false) {
        synchronized(connectLock) {
            if (!expertReserved.getAndSet(false)) return
            val lease = simpleRestoreLease
            simpleRestoreLease = null
            synchronized(this) restore@ {
                if (restorePrevious && lease?.permitsRestore(connectGeneration.get(), state.value.saved,
                        tunnel.hasOwnedProcess()) == true) {
                    simpleRestriction()?.let { restriction ->
                        desiredConnection = false
                        reportConnectionError(restriction)
                        return@restore
                    }
                    val previous = lease.connection
                    val ticket = connectGeneration.incrementAndGet()
                    desiredConnection = true
                    activeSimpleConnection = WindowsSimpleConnection(previous.launch, previous.profileId, previous.mode,
                        previous.desiredSnapshot, ticket)
                    try {
                        tunnel.start(previous.launch)
                        mutable.update { it.copy(message = "Экспертный режим не запущен · восстанавливаем предыдущее подключение") }
                    } catch (error: Exception) {
                        desiredConnection = false
                        reportConnectionError(app.lernet.engine.text.UserText.withReason("Не удалось восстановить предыдущее подключение", error))
                    }
                }
            }
        }
    }

    fun workspaceDirectory(): Path = store.workDirectory()

    /** Called inside the shared import journal transaction; IDs must remain stable. */
    fun commitWorkspaceInventory(bundle: TransferBundle) {
        check(change { DesktopTransfer.materializeWorkspace(it, bundle) }) { "Не удалось сохранить профили из рабочей области" }
    }

    /** A form must not overwrite a concurrent import, profile edit or selection. */
    fun commitWorkspaceInventoryIfUnchanged(before: TransferBundle, after: TransferBundle) {
        check(change { saved ->
            check(TransferCodec.decode(DesktopTransfer.export(saved)) == before) {
                "Список профилей изменился. Обновите форму перед сохранением."
            }
            DesktopTransfer.materializeWorkspace(saved, after)
        }) { "Не удалось сохранить внешний выход" }
    }

    private fun change(block: (StoredState) -> StoredState): Boolean {
        synchronized(this) {
            if (loaded.isFailure) {
                mutable.update { it.copy(message = "Файл профилей повреждён. Изменения не сохранены.") }
                return false
            }
            val next = block(mutable.value.saved)
            runCatching { store.save(next) }.onFailure { error ->
                mutable.update { it.copy(message = app.lernet.engine.text.UserText.withReason("Не удалось сохранить", error)) }
                return false
            }
            mutable.update { current -> current.copy(saved = next,
                networkWarning = if (current.saved.selectedProfileId != next.selectedProfileId) "" else current.networkWarning) }
            return true
        }
    }

    override fun close() {
        synchronized(connectLock) {
            desiredConnection = false
            connectGeneration.incrementAndGet()
            tunnel.stop(waitForExit = true)
            releaseGuard()
        }
        monitorOpen = false
        monitorThread.interrupt()
        tracer.close()
        probePool.shutdownNow()
    }
}

/** Resting configs ([ConfigPenalties]) in the work folder, one line per config. */
private const val PENALTIES_FILE = "config-penalties.txt"

/** A free loopback port for the internal listener; sing-box binds it right after. */
internal fun freeLoopbackPort(): Int =
    java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }

/** What the main screen says about the leak guard; null while it simply works. */
internal fun guardProblemText(status: SimpleGuardStatus): String? = when (status) {
    is SimpleGuardStatus.Unavailable ->
        if (status.networkClosed) SecurityTexts.GUARD_NETWORK_CLOSED else SecurityTexts.GUARD_PROBLEM
    else -> null
}

/** Import result line; unused parts of the config are named on screen, not only in the journal. */
internal fun importMessage(added: Int, unusedParts: List<String>): String =
    if (unusedParts.isEmpty()) "Добавлено профилей: $added"
    else "Добавлено профилей: $added. Эти части конфига не используются: ${unusedParts.joinToString("; ")}"
