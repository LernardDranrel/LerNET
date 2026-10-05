package app.lernet.desktop

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
import java.util.concurrent.ThreadLocalRandom
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
)

class DesktopController(
    private val store: DesktopStore = DesktopStore(),
) : AutoCloseable {
    @Volatile internal var simpleModeRestriction: (() -> String?)? = null

    private fun simpleRestriction(): String? = try {
        simpleModeRestriction?.invoke()
    } catch (_: Exception) {
        "Не удалось проверить системную защиту. Откройте экспертный режим и проверьте раздел «Защита»."
    }
    private val loaded = runCatching { store.load() }
    private val mutable = MutableStateFlow(
        DesktopUiState(
            saved = loaded.getOrDefault(StoredState()),
            message = loaded.exceptionOrNull()?.let { "Не удалось прочитать профили: ${it.message}" }
                ?: if (store.recoveredFromBackup) "Профили восстановлены из резервной копии" else "",
        ),
    )
    val state: StateFlow<DesktopUiState> = mutable
    val tunnel = WindowsBoxProcess(store.workDirectory())
    val tracer = WindowsTracer()
    private val coreApi = LocalCoreApi()
    private val mutableDiagnostics = MutableStateFlow(CoreDiagnostics())
    val diagnostics: StateFlow<CoreDiagnostics> = mutableDiagnostics
    private val observedConnections = LinkedHashMap<String, LiveConnection>()
    private val mutableHistory = MutableStateFlow<List<LiveConnection>>(emptyList())
    val connectionHistory: StateFlow<List<LiveConnection>> = mutableHistory
    private val failedInGroup = mutableSetOf<String>()
    @Volatile private var desiredConnection = false
    private val expertReserved = AtomicBoolean(false)
    private var activeSimpleConnection: WindowsSimpleConnection? = null
    private var simpleRestoreLease: WindowsSimpleRestoreLease? = null
    @Volatile private var monitorOpen = true
    private val monitorThread = thread(name = "lernet-windows-diagnostics", isDaemon = true) {
        var lastStatus = TunnelStatus.STOPPED
        var nextHealthAt = 0L
        var failedChecks = 0
        var lastNetwork = runCatching { WindowsTunnelHealth.networkSignature() }.getOrDefault("")
        var lastNetworkScanAt = 0L
        var networkChangedAt = 0L
        while (monitorOpen) {
            val status = tunnel.state.value.status
            if (status == TunnelStatus.RUNNING) {
                mutableDiagnostics.value = runCatching { coreApi.poll() }
                    .getOrElse { previous -> CoreDiagnostics(error = previous.message ?: "Нет данных от ядра") }
                recordConnections(mutableDiagnostics.value.connections)
                if (lastStatus != TunnelStatus.RUNNING) {
                    nextHealthAt = 0L
                    failedChecks = 0
                    mutable.update { it.copy(message = "", healthMessage = "Проверяем соединение", healthFailures = 0, healthVerified = null, tunnelLatencyMs = null) }
                }
                val now = System.currentTimeMillis()
                if (now - lastNetworkScanAt >= 3_000) {
                    lastNetworkScanAt = now
                    runCatching { WindowsTunnelHealth.networkSignature() }.onSuccess { signature ->
                        if (signature != lastNetwork) {
                            lastNetwork = signature
                            networkChangedAt = now
                            mutable.update { it.copy(healthMessage = "Сеть изменилась · проверяем VPN", healthVerified = null) }
                        }
                    }
                }
                if (now >= nextHealthAt || networkChangedAt != 0L && now - networkChangedAt >= 3_000) {
                    networkChangedAt = 0L
                    val saved = state.value.saved
                    val profile = saved.profiles.firstOrNull { it.id == saved.selectedProfileId }
                    val tag = profile?.selectedOutbound?.tag
                    if (tag != null) {
                        val ticket = connectGeneration.get()
                        val health = WindowsTunnelHealth.check(saved.healthUrl, effectiveMode(profile))
                        if (ticket == connectGeneration.get() && tunnel.state.value.status == TunnelStatus.RUNNING) {
                            if (health.routeConflict) {
                                failedChecks = 0
                                mutable.update { it.copy(healthMessage = health.error, healthFailures = 2,
                                    healthVerified = false, tunnelLatencyMs = null) }
                            } else if (health.latencyMs != null) {
                                failedChecks = 0
                                mutable.update { it.copy(healthMessage = "VPN отвечает · ${health.latencyMs} мс", healthFailures = 0,
                                    healthVerified = true, tunnelLatencyMs = health.latencyMs) }
                            } else {
                                val nodeAlive = runCatching { coreApi.delay(tag, saved.healthUrl, 4_000) }.isSuccess
                                if (ticket == connectGeneration.get() && tunnel.state.value.status == TunnelStatus.RUNNING) {
                                    failedChecks++
                                    val reason = if (nodeAlive) "Узел отвечает, но путь Windows через VPN не работает"
                                        else "Трафик через VPN не проходит"
                                    mutable.update { it.copy(healthMessage = "$reason · ${health.error}",
                                        healthFailures = failedChecks, healthVerified = false, tunnelLatencyMs = null) }
                                    if (failedChecks >= 2 && desiredConnection) {
                                        val swapped = tryFailover(profile.id)
                                        if (!swapped && desiredConnection) {
                                            disconnect()
                                            connectInternal(resetFailover = true)
                                        }
                                        failedChecks = 0
                                    }
                                }
                            }
                        }
                    }
                    // Wait from completion, so a slow request never overlaps the next check.
                    nextHealthAt = System.currentTimeMillis() + if (failedChecks > 0) 2_000L
                        else ThreadLocalRandom.current().nextLong(5_000L, 10_001L)
                }
            } else if (mutableDiagnostics.value != CoreDiagnostics()) {
                mutableDiagnostics.value = CoreDiagnostics()
                recordConnections(emptyList())
            }
            if (status == TunnelStatus.FAILED && lastStatus != TunnelStatus.FAILED) {
                val permissionFailure = tunnel.state.value.failure == TunnelFailure.TUN_PERMISSION
                if (permissionFailure) desiredConnection = false
                val swapped = !permissionFailure && state.value.saved.selectedProfileId?.let(::tryFailover) == true
                if (!swapped && desiredConnection && tunnel.state.value.message.contains("попытки исчерпаны")) {
                    val ticket = connectGeneration.get()
                    thread(name = "lernet-windows-retry", isDaemon = true) {
                        Thread.sleep(15_000)
                        if (desiredConnection && connectGeneration.get() == ticket &&
                            tunnel.state.value.status in setOf(TunnelStatus.FAILED, TunnelStatus.STOPPED)) {
                            connectInternal(resetFailover = false)
                        }
                    }
                }
            }
            if (status == TunnelStatus.STOPPED && lastStatus != TunnelStatus.STOPPED) {
                mutable.update { it.copy(healthMessage = "", healthFailures = 0, healthVerified = null, tunnelLatencyMs = null) }
            }
            lastStatus = status
            try { Thread.sleep(1_200) } catch (_: InterruptedException) { break }
        }
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
        profiles.forEach { profile ->
            val ticket = probeSequence.incrementAndGet()
            probeTickets[profile.id] = ticket
            mutable.update { it.copy(probes = it.probes + (profile.id to ProbeResult(
                message = "Проверяем ${EndpointProbe.protocol(profile)}", checking = true,
                tunnelMessage = "Ожидает проверки", serverProtocol = EndpointProbe.protocol(profile),
            ))) }
            probePool.submit {
                val tcp = EndpointProbe.check(profile)
                if (probeTickets[profile.id] != ticket) return@submit
                mutable.update { it.copy(probes = it.probes + (profile.id to tcp.copy(
                    checking = true, tunnelMessage = "Проверяем канал через узел…",
                ))) }
                val outbound = checkOutbound(profile, saved)
                if (probeTickets[profile.id] != ticket) return@submit
                mutable.update { it.copy(probes = it.probes + (profile.id to tcp.copy(
                    tunnelLatencyMs = outbound.latencyMs,
                    tunnelMessage = outbound.message,
                ))) }
            }
        }
    }

    private fun checkOutbound(profile: StoredProfile, saved: StoredState): OutboundProbeResult {
        observeEndpointRoute(profile)
        val result = runCatching {
            val executable = saved.corePath.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: synchronized(coreInstallLock) { BundledCore.install(store.workDirectory()) }
            OutboundProbe.check(profile, saved, executable, store.workDirectory())
        }.getOrElse { OutboundProbeResult(message = it.message?.take(180) ?: "Не удалось проверить канал") }
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
                .onFailure { error -> mutable.update { it.copy(busy = false, message = "Экспорт не выполнен: ${error.message}") } }
        }
    }

    fun importFrom(path: Path) {
        thread(name = "lernet-windows-read-transfer", isDaemon = true) {
            runCatching {
                check(Files.size(path) <= 20_000_000) { "Архив больше 20 МБ" }
                Files.readString(path)
            }.onSuccess(::importBundle)
                .onFailure { error -> mutable.update { it.copy(message = "Не удалось прочитать архив: ${error.message}") } }
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
                .onFailure { error -> mutable.update { it.copy(busy = false, message = "Импорт не выполнен: ${error.message}") } }
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
            }.getOrElse { ImportResult.Failure(listOf(app.lernet.config.parse.FieldError("import", it.message ?: "Ошибка импорта"))) }
            when (result) {
                is ImportResult.Success -> change { saved ->
                    val added = result.drafts.map { StoredProfile.fromDraft(it).copy(groupId = groupId) }
                    saved.copy(
                        profiles = saved.profiles + added,
                        selectedProfileId = saved.selectedProfileId ?: added.firstOrNull()?.id,
                    )
                }.also { mutable.update { it.copy(busy = false, message = "Добавлено профилей: ${result.drafts.size}") } }
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
                reportConnectionError(error.message ?: "Не удалось переключить подключение")
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
    fun setDefaultDnsPolicy(policy: DnsPolicy) = change { it.copy(defaultDnsPolicy = policy.name) }
    fun setJournalMaxMb(value: Int) = change { it.copy(journalMaxMb = value.coerceIn(1, 500)) }
    fun setDefaults(mtu: Int, xmux: String, dnsServer: String, logLevel: String) = change {
        it.copy(tunMtu = mtu.coerceIn(1280, 9000), xmuxConcurrency = xmux.trim(),
            directDnsServer = dnsServer.trim(), logLevel = logLevel)
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
                             draftRules: List<StoredRule>? = null, draftOwnerId: String? = null): AssembledConfig {
        val saved = if (draftRules != null && draftOwnerId != null) source.copy(
            rules = source.rules.filterNot { it.profileId == draftOwnerId } + draftRules
        ) else source
        val profile = draftProfile ?: saved.profiles.firstOrNull { it.id == profileId }
            ?: return AssembledConfig("", "", listOf("Выберите профиль"))
        val outbound = profile.selectedOutbound
            ?: return AssembledConfig("", "", listOf("В профиле нет рабочего outbound"))
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
            }.getOrDefault(DnsPolicy.UNDERLAY),
            ruleSetDirectory = ruleSetDir.toString(),
            defaults = EngineDefaults(saved.tunMtu, saved.xmuxConcurrency, saved.directDnsServer),
            platform = EnginePlatform.WINDOWS,
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

    private fun connectInternal(resetFailover: Boolean) {
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
        if (resetFailover) synchronized(failedInGroup) { failedInGroup.clear() }
        val ticket = synchronized(connectLock) {
            if (expertReserved.get()) {
                connectBusy.set(false)
                reportConnectionError("Устройством управляет экспертный режим. Остановите его перед включением обычного VPN.")
                return
            }
            desiredConnection = true
            connectGeneration.incrementAndGet()
        }
        val savedAtStart = state.value.saved
        val profileAtStart = savedAtStart.profiles.firstOrNull { it.id == savedAtStart.selectedProfileId }
        mutable.update { it.copy(busy = true, message = "Подготовка Windows-ядра", connectionError = null) }
        thread(name = "lernet-windows-connect", isDaemon = true) {
            var routeErrorOwner: String? = null
            try {
                val assembled = previewSaved(savedAtStart, profileAtStart?.id)
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
                        activeSimpleConnection = WindowsSimpleConnection(launch, profile.id,
                            DesktopRunMode.effective(profile.modeOverride ?: savedAtStart.mode, WindowsElevation.isElevated),
                            savedAtStart, ticket)
                        tunnel.start(launch)
                    }
                }
                mutable.update { it.copy(message = "Запускаем ядро") }
            } catch (error: Exception) {
                if (connectGeneration.get() == ticket) {
                    desiredConnection = false
                    reportConnectionError(error.message ?: "Не удалось запустить ядро", routeErrorOwner)
                }
            } finally {
                connectBusy.set(false)
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun reportConnectionError(message: String, routeOwnerId: String? = null) {
        tunnel.logDiagnostic("Подключение не запущено: $message")
        mutable.update { it.copy(message = message, connectionError = ConnectionError(message, routeOwnerId)) }
    }

    private fun tryFailover(failedProfileId: String): Boolean {
        val saved = state.value.saved
        val failed = saved.profiles.firstOrNull { it.id == failedProfileId } ?: return false
        val group = saved.groups.firstOrNull { it.id == failed.groupId && it.autoSwap } ?: return false
        synchronized(failedInGroup) { failedInGroup += failedProfileId }
        val candidates = saved.profiles.filter { it.groupId == group.id && it.id != failedProfileId &&
            synchronized(failedInGroup) { it.id !in failedInGroup } }
        if (candidates.isEmpty()) return false
        // Probe the underlay after removing the broken TUN route. Keep the user's
        // connection intent so a failed folder search can still retry later.
        synchronized(connectLock) {
            connectGeneration.incrementAndGet()
            tunnel.stop(waitForExit = true)
        }
        for (candidate in candidates) {
            val tcp = EndpointProbe.check(candidate)
            val outbound = checkOutbound(candidate, saved)
            val probe = tcp.copy(tunnelLatencyMs = outbound.latencyMs, tunnelMessage = outbound.message)
            mutable.update { it.copy(probes = it.probes + (candidate.id to probe)) }
            if (probe.tunnelLatencyMs != null) {
                change { it.copy(selectedProfileId = candidate.id) }
                mutable.update { it.copy(message = "Переключаемся на ${candidate.name} из папки «${group.name}»") }
                connectInternal(resetFailover = false)
                return true
            }
            synchronized(failedInGroup) { failedInGroup += candidate.id }
        }
        mutable.update { it.copy(message = "В папке «${group.name}» нет доступной замены") }
        return false
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
        }
        mutable.update { it.copy(message = "Отключено") }
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
                        reportConnectionError("Не удалось восстановить предыдущее подключение: ${error.message}")
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
                mutable.update { it.copy(message = "Не удалось сохранить: ${error.message}") }
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
        }
        monitorOpen = false
        monitorThread.interrupt()
        tracer.close()
        probePool.shutdownNow()
    }
}
