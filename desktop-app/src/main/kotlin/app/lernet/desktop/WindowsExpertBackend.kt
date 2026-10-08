package app.lernet.desktop

import app.lernet.config.redact.SecretRedactor
import app.lernet.desktop.protection.ProtectionLease
import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.ExpertBackendEvent
import app.lernet.engine.policy.ExpertConnectionObservation
import app.lernet.engine.policy.ExpertExitKey
import app.lernet.engine.policy.ExpertExitState
import app.lernet.engine.policy.ExpertNativeStatusEvidence
import app.lernet.engine.policy.ExpertProbeResult
import app.lernet.engine.policy.ExpertRuntimeBackend
import app.lernet.engine.policy.ExpertStateUncertainException
import app.lernet.engine.policy.ExpertTunnelAck
import app.lernet.engine.policy.FlowInspection
import app.lernet.engine.policy.PolicyAssembledConfig
import app.lernet.engine.policy.PolicyControlCapabilities
import app.lernet.engine.policy.TunIdentity
import app.lernet.engine.policy.expertNativeFailureExplanation
import app.lernet.routing.policy.ExitLifecyclePolicy
import app.lernet.routing.policy.PolicyProgram
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Owns one native process. Policy replacement never launches a second ingress. */
internal interface WindowsExpertOwner {
    fun workspaceDirectory(): Path
    fun reserveExpertMode()
    fun releaseExpertMode(restorePrevious: Boolean = false)
}

internal class WindowsExpertBackend(
    private val simple: WindowsExpertOwner,
    private val scope: CoroutineScope,
    private val assemble: (PolicyProgram) -> PolicyAssembledConfig,
    private val probeUrl: () -> String,
    private val log: (String) -> Unit,
    private val controlReady: (Path, Int) -> Unit = { _, _ -> },
    private val elevated: () -> Boolean = { WindowsElevation.isElevated },
    private val prepareBinary: ((Path) -> Path)? = null,
    private val verifyRoutes: (ExpertNativeAck) -> String? = { WindowsRouteInspector.checkExpert(it.interfaceId) },
    private val physicalNetwork: () -> String = { WindowsPhysicalNetwork.select().name },
    private val spawn: (List<String>, Path) -> Process = { arguments, directory ->
        ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true).start()
    },
    private val prepareIngress: (Path, String, () -> ProtectionLease) -> JsonObject? = { _, _, _ -> null },
    private val beforeStop: () -> Unit = {},
    private val prepareControlSecret: (Path, String) -> Path = ::createControlSecret,
    private val permitIngress: (ExpertNativeAck, () -> ProtectionLease) -> Unit = { _, _ -> },
) : ExpertRuntimeBackend {
    private data class OwnedControl(val process: Process, val client: ExpertControlClient, val epoch: Long)
    private data class Applied(val ack: ExpertNativeAck, val config: PolicyAssembledConfig, val owner: OwnedControl)
    private val lifecycleLock = Any()
    private val epoch = AtomicLong()
    val operationGeneration: Long get() = epoch.get()
    private val mutableEvents = MutableSharedFlow<ExpertBackendEvent>(extraBufferCapacity = 1024)

    @Volatile private var process: Process? = null

    @Volatile private var client: ExpertControlClient? = null

    @Volatile private var applied: Applied? = null

    @Volatile private var tagBindings: Map<String, ExpertExitKey> = emptyMap()

    @Volatile private var negotiated = PolicyControlCapabilities.RESTART_ONLY

    @Volatile private var negotiatedEpoch: Long? = null

    @Volatile private var nativeNetworkEpoch: Long? = null

    @Volatile var underlayInterface: String? = null
        private set

    @Volatile var corePath: Path? = null
        private set

    @Volatile var controlPort: Int? = null
        private set
    private var polling: Job? = null
    private var reserved = false
    private var revocationPending = false
    override val capabilities get() = negotiated
    override val events = mutableEvents.asSharedFlow()
    val assembled: PolicyAssembledConfig? get() = applied?.config
    val identity: TunIdentity? get() = applied?.ack?.identity()
    fun processLease(): ProtectionLease? = process?.takeIf { it.isAlive }?.let(::leaseFor)

    override suspend fun negotiateCapabilities(): PolicyControlCapabilities {
        val ticket = epoch.get()
        return withContext(Dispatchers.IO) {
            val owner = ensureControl(ticket)
            val result = owner.client.capabilities()
            synchronized(lifecycleLock) {
                check(isCurrent(owner)) { "Проверка возможностей отменена" }
                negotiated = result
                negotiatedEpoch = ticket
            }
            result
        }
    }

    override suspend fun start(program: PolicyProgram): ExpertTunnelAck = withContext(Dispatchers.IO) {
        val ticket = requireNotNull(negotiatedEpoch) { "Запуск управления отменён; повторите включение" }
        check(ticket == epoch.get()) { "Запуск был отменён" }
        check(elevated()) { "Экспертному режиму нужны права администратора для общего TUN" }
        val owner = ensureControl(ticket)
        val local = owner.client
        check(capabilities.preservesTun && capabilities.atomicRules && capabilities.independentExits) {
            "Комплектное ядро не подтвердило постоянный TUN и независимые выходы"
        }
        val config = assemble(program)
        check(config.isValid) { config.errors.joinToString("\n") }
        synchronized(lifecycleLock) {
            check(isCurrent(owner)) { "Запуск был отменён" }
            check(applied == null) { "Экспертный режим уже запущен" }
            simple.reserveExpertMode()
            reserved = true
        }
        try {
            val ingress = Json.parseToJsonElement(config.ingressJson) as JsonObject
            val tunName = (ingress["inbounds"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it.nativeString("type") == "tun" }
                ?.nativeString("interface_name", 256)
                ?: error("В конфигурации отсутствует имя общего TUN")
            val guardedAdapter = prepareIngress(requireNotNull(corePath), tunName) { leaseFor(owner.process) }
            synchronized(lifecycleLock) { check(isCurrent(owner)) { "Запуск был отменён" } }
            val response = local.request(
                "start",
                buildJsonObject {
                    put("revision", program.revision)
                    put("ingress", ingress)
                    put("policy", Json.parseToJsonElement(config.policyJson))
                    put("exits", Json.parseToJsonElement(config.exitManifestJson))
                    if (guardedAdapter != null) put("guarded_adapter", guardedAdapter)
                }
            )
            val ack = local.acknowledgement(response)
            check(ack.revision == program.revision) { "Ядро подтвердило другую версию схемы" }
            verifyRoutes(ack)?.let { error(it) }
            synchronized(lifecycleLock) {
                check(isCurrent(owner)) { "Запуск был отменён" }
                permitIngress(ack) { leaseFor(owner.process) }
                check(isCurrent(owner)) { "Запуск был отменён" }
                applied = Applied(ack, config, owner)
                tagBindings = config.exitTags.entries.associate { it.value to it.key }
            }
            config.notes.forEach { log("Схема применена: $it") }
            startPolling(owner)
            ExpertTunnelAck(ack.identity(), ack.revision)
        } catch (failure: Exception) {
            // An explicit Stop records its epoch before waiting for cleanup and must never revive Simple.
            val restorePrevious = epoch.get() == ticket
            shutdownOwnedProcess(owner, restorePrevious)
            throw failure
        }
    }

    override suspend fun apply(program: PolicyProgram, expected: TunIdentity): ExpertTunnelAck = withContext(Dispatchers.IO) {
        val before = requireApplied(expected)
        val config = assemble(program)
        check(config.isValid) { config.errors.joinToString("\n") }
        val local = before.owner.client
        val ack = try {
            local.acknowledgement(
                local.request(
                    "apply",
                    identityPayload(before.ack) {
                        put("revision", program.revision)
                        put("policy", Json.parseToJsonElement(config.policyJson))
                        put("exits", Json.parseToJsonElement(config.exitManifestJson))
                    }
                )
            )
        } catch (failure: Exception) {
            // A lost response can occur after native publication. Only status can prove which plan is active.
            val actual = runCatching {
                val body = local.request("status", timeoutMs = 4_000)
                check(body.nativeBoolean("running") == true) { "Ядро не подтвердило работающий TUN" }
                local.acknowledgement(body)
            }.getOrNull()
            when {
                actual?.identity() == expected && actual.revision == program.revision -> actual
                actual == before.ack && failure is ExpertControlRejectedException && failure.status in 400..499 -> throw failure
                else -> {
                    shutdownOwnedProcess(before.owner)
                    throw ExpertStateUncertainException(
                        "Ядро не подтвердило активную версию. Общий TUN остановлен; повторите запуск.", failure
                    )
                }
            }
        }
        if (ack.identity() != expected || ack.revision != program.revision) {
            shutdownOwnedProcess(before.owner)
            throw ExpertStateUncertainException("Идентификатор TUN или версия схемы изменились без подтверждения")
        }
        synchronized(lifecycleLock) {
            check(applied === before && isCurrent(before.owner)) { "Применение было отменено" }
            applied = Applied(ack, config, before.owner)
            tagBindings = tagBindings + config.exitTags.entries.associate { it.value to it.key }
        }
        config.notes.forEach { log("Схема применена: $it") }
        ExpertTunnelAck(expected, ack.revision)
    }

    override suspend fun stop(expected: TunIdentity?) = withContext(Dispatchers.IO) {
        val before = applied
        check(expected == null || before == null || before.ack.identity() == expected) { "Нельзя остановить другой TUN" }
        // Killing only our owned process also cancels native preparation and blocking outbound probes.
        shutdownOwnedProcess()
    }

    override suspend fun wakeExit(key: ExpertExitKey, generation: Long, lifecycle: ExitLifecyclePolicy) = withContext(Dispatchers.IO) {
        val before = requireApplied()
        val tag = before.config.exitTags[key] ?: error("Выход отсутствует в активной схеме")
        before.owner.client.request("wake", identityPayload(before.ack) { put("tag", tag) }, lifecycle.startupTimeoutMs.toInt())
        Unit
    }

    override suspend fun sleepExit(key: ExpertExitKey, generation: Long) = withContext(Dispatchers.IO) {
        val before = requireApplied()
        val tag = before.config.exitTags[key] ?: return@withContext
        before.owner.client.request("sleep", identityPayload(before.ack) { put("tag", tag) }, 4_000)
        Unit
    }

    override suspend fun recoverExit(key: ExpertExitKey, generation: Long, lifecycle: ExitLifecyclePolicy) = withContext(Dispatchers.IO) {
        val before = requireApplied()
        val tag = before.config.exitTags[key] ?: error("Выход отсутствует в активной схеме")
        before.owner.client.request("recover", identityPayload(before.ack) { put("tag", tag) }, lifecycle.startupTimeoutMs.toInt())
        Unit
    }

    suspend fun networkChanged() = withContext(Dispatchers.IO) {
        val before = applied ?: return@withContext
        val underlay = physicalNetwork()
        before.owner.client.request("network_changed", identityPayload(before.ack) { put("underlay_interface", underlay) }, 4_000)
        // The native watcher retains ingress and reports transient route failures.
        // A failed verification must not release the TUN behind the user's back.
        verifyRoutes(before.ack)?.let { conflict ->
            mutableEvents.emit(ExpertBackendEvent.NetworkStatus(before.ack.identity(), before.ack.revision, conflict))
        }
        Unit
    }

    override suspend fun probeExit(key: ExpertExitKey, timeoutMs: Long): ExpertProbeResult = withContext(Dispatchers.IO) {
        val before = requireApplied()
        val tag = before.config.exitTags[key] ?: return@withContext ExpertProbeResult(null, "Выход отсутствует в активной схеме")
        val budget = timeoutMs.coerceIn(1_000, 45_000).toInt()
        val response = before.owner.client.request(
            "probe",
            identityPayload(before.ack) {
                put("tag", tag)
                put("url", probeUrl())
                put("timeout_ms", budget)
            },
            (budget + 1_000).coerceAtMost(60_000)
        )
        ExpertProbeResult(
            response.nativeLong("https_latency_ms")?.takeIf { it > 0 },
            response.nativeString("reason", 256)?.let(::nativeReason)
        )
    }

    private fun ensureControl(ticket: Long): OwnedControl = synchronized(lifecycleLock) {
        check(ticket == epoch.get()) { "Запуск управления отменён" }
        if (revocationPending) {
            throw ExpertStateUncertainException("Windows не подтвердила отзыв разрешения TUN; повторите «Остановить»")
        }
        val existing = process
        client?.takeIf { existing?.isAlive == true }?.let {
            return@synchronized OwnedControl(requireNotNull(existing), it, ticket)
        }
        check(elevated()) { "Запустите LerNET от имени администратора для экспертного режима" }
        val directory = simple.workspaceDirectory().resolve("expert-runtime")
        Files.createDirectories(directory)
        val secured = prepareBinary?.invoke(directory) ?: WindowsExpertProtection.prepareProtectedCore(installCore(directory)).getOrThrow()
        check(ticket == epoch.get()) { "Запуск управления отменён" }
        corePath = secured
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
        val tokenFile = prepareControlSecret(secured, token)
        val privateDirectory = tokenFile.parent
        val ready = CompletableFuture<Int>()
        val child = try {
            spawn(
                listOf(
                    secured.toString(), "expert", "--listen", "127.0.0.1:0",
                    "--owner-pid", ProcessHandle.current().pid().toString(),
                    "--owner-started-ms", ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli().toString(),
                    "--token-file", tokenFile.toString(),
                ),
                secured.parent
            )
        } catch (failure: Exception) {
            Files.deleteIfExists(tokenFile)
            Files.deleteIfExists(privateDirectory)
            throw failure
        }
        process = child
        thread(name = "lernet-expert-output", isDaemon = true) {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.startsWith("LERNET_CONTROL_READY ")) {
                            val value = Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject
                            val port = value.nativeInt("port")
                            check(port != null && port in 1..65535)
                            ready.complete(port)
                        } else {
                            try {
                                log(SecretRedactor.redact(line.replace(token, "***")).take(4_000))
                            } catch (failure: Exception) {
                                // A failed journal must not leave a live core with an undrained output pipe.
                                child.destroy()
                                throw IllegalStateException("Не удалось сохранить журнал ядра", failure)
                            }
                        }
                    }
                }
                ready.completeExceptionally(IllegalStateException("Ядро завершилось до открытия управления"))
            } catch (failure: Exception) {
                ready.completeExceptionally(IllegalStateException("Не удалось прочитать состояние ядра", failure))
            }
        }
        try {
            val port = ready.get(30, TimeUnit.SECONDS)
            check(ticket == epoch.get()) { "Запуск управления отменён" }
            controlPort = port
            val local = ExpertControlClient(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), token).also {
                client = it
                controlReady(secured, port)
            }
            OwnedControl(child, local, ticket)
        } catch (failure: Exception) {
            child.destroyForcibly()
            check(child.waitFor(5, TimeUnit.SECONDS)) { "Windows не подтвердила отмену запуска ядра" }
            process = null
            client = null
            controlPort = null
            throw IllegalStateException("Ядро не открыло локальное управление за 30 секунд", failure)
        } finally {
            Files.deleteIfExists(tokenFile)
            Files.deleteIfExists(privateDirectory)
        }
    }

    private fun startPolling(owner: OwnedControl) = synchronized(lifecycleLock) {
        if (!isCurrent(owner)) return@synchronized
        polling?.cancel()
        polling = scope.launch(Dispatchers.IO) {
            var failed = 0
            while (epoch.get() == owner.epoch) {
                val snapshot = applied ?: break
                if (snapshot.owner.process !== owner.process || snapshot.owner.client !== owner.client) break
                try {
                    val response = owner.client.request("status", timeoutMs = 4_000)
                    if (!receiveStatus(response, snapshot)) break
                    failed = 0
                } catch (failure: Exception) {
                    if (epoch.get() != owner.epoch) break
                    if (!owner.process.isAlive) {
                        mutableEvents.emit(
                            ExpertBackendEvent.TunnelLost(
                                snapshot.ack.identity(),
                                "Связь с ядром потеряна: ${SecretRedactor.redact(failure.message.orEmpty()).take(300)}"
                            )
                        )
                        break
                    }
                    if (++failed >= 3) {
                        mutableEvents.emit(ExpertBackendEvent.NetworkStatus(
                            snapshot.ack.identity(), snapshot.ack.revision,
                            "Нет ответа от ядра. TUN не отключаем; повторяем проверку.",
                        ))
                    }
                }
                delay(1_000)
            }
        }
    }

    private suspend fun receiveStatus(body: JsonObject, snapshot: Applied): Boolean {
        if (applied !== snapshot) return true
        val actual = snapshot.owner.client.acknowledgement(body)
        check(actual == snapshot.ack) { "Ядро сообщило другую идентичность или версию TUN" }
        if (body.nativeBoolean("running") != true) {
            val code = body.nativeString("stop_reason", 256)
            val reason = code?.let(::nativeReason) ?: "Ядро не подтвердило работающий TUN"
            mutableEvents.emit(ExpertBackendEvent.TunnelLost(actual.identity(), reason))
            return false
        }
        mutableEvents.emit(
            ExpertBackendEvent.NetworkStatus(
                actual.identity(), actual.revision,
                body.nativeString("network_reason", 256)?.takeIf { it.isNotBlank() }?.let(::nativeReason),
            )
        )
        mutableEvents.emit(
            ExpertBackendEvent.RetiredCleanupSnapshot(
                ExpertNativeStatusEvidence.retiredCleanupFailures(body), actual.identity(), actual.revision,
            )
        )
        val networkEpoch = body.nativeLong("network_epoch")
        val underlay = body.nativeString("underlay_interface", 256)
        val previousNetworkEpoch = nativeNetworkEpoch
        if (networkEpoch != null && previousNetworkEpoch != null && networkEpoch < previousNetworkEpoch) return true
        if (networkEpoch != null && previousNetworkEpoch != null && networkEpoch > previousNetworkEpoch) {
            mutableEvents.emit(ExpertBackendEvent.NetworkChanged(actual.identity(), actual.revision, networkEpoch))
        }
        nativeNetworkEpoch = networkEpoch ?: previousNetworkEpoch
        underlayInterface = underlay ?: underlayInterface
        val byTag = tagBindings
        val exits = (body["exits"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val value = entry as? JsonObject ?: return@mapNotNull null
            val key = value.nativeString("tag")?.let(byTag::get) ?: return@mapNotNull null
            val phase = nativeExitPhase(value)
            val reason = value.nativeString("reason", 256)?.let(::nativeReason)
                ?: if (phase == ExitPhase.READY && value.nativeString("health", 64) != "healthy") {
                    "Работа выхода через HTTPS пока не подтверждена"
                } else {
                    null
                }
            ExpertExitState(
                key, phase, latencyMs = value.nativeLong("latency_ms")?.takeIf { it > 0 }, reason = reason,
                activeFlows = value.nativeInt("active_flows") ?: 0,
                pendingFlows = value.nativeInt("pending_flows") ?: 0,
                lastCheckMs = value.nativeLong("last_check_ms")
            )
        }
        mutableEvents.emit(ExpertBackendEvent.ExitsSnapshot(exits, actual.identity(), revision = actual.revision))
        val folders = (body["folders"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val value = entry as? JsonObject ?: return@mapNotNull null
            val tag = value.nativeString("tag") ?: return@mapNotNull null
            if (snapshot.config.folders.none { it.tag == tag }) return@mapNotNull null
            val selected = value.nativeString("selected_tag")?.let(byTag::get) ?: return@mapNotNull null
            tag to selected
        }.toMap()
        mutableEvents.emit(ExpertBackendEvent.FolderSelectionsSnapshot(folders, actual.identity(), revision = actual.revision))
        val flowRows = (body["flows"] as? JsonArray).orEmpty()
        val observedAtMs = System.currentTimeMillis()
        app.lernet.engine.policy.directNetworkFacts(body, observedAtMs)?.let {
            mutableEvents.emit(ExpertBackendEvent.DirectNetworkSnapshot(it, actual.identity(), actual.revision))
        }
        mutableEvents.emit(
            ExpertBackendEvent.ObservationHistory(
                droppedCount = body.nativeLong("flow_dropped_count") ?: 0,
                limit = body.nativeInt("flow_history_limit") ?: 500,
                identity = actual.identity(),
                visibleFlowIds = flowRows.mapNotNull { (it as? JsonObject)?.let(ExpertNativeStatusEvidence::flowId) }.toSet(),
            ),
        )
        (body["flows"] as? JsonArray).orEmpty().forEach { entry ->
            val flow = entry as? JsonObject ?: return@forEach
            val id = ExpertNativeStatusEvidence.flowId(flow) ?: return@forEach
            val destination = flow.nativeString("destination", 4096) ?: return@forEach
            val tag = flow.nativeString("selected_outbound") ?: flow.nativeString("outbound")
            val key = tag?.let(byTag::get)
            val decision = when {
                flow.nativeString("state", 64) in setOf("blocked", "rejected") -> "Заблокировано"
                tag == "direct" -> "Напрямую"
                tag in setOf("block", "reject") -> "Заблокировано"
                tag == null -> "Решение не передано ядром"
                key != null -> "Через настроенный выход"
                else -> "Через другой выход"
            } + flow.nativeString("reason", 256)?.let { ": ${nativeReason(it)}" }.orEmpty()
            mutableEvents.emit(
                ExpertBackendEvent.Observation(
                    ExpertConnectionObservation(
                        id = id, application = flow.nativeString("process", 4096),
                        destination = destination, protocol = flow.nativeString("network", 64) ?: "?",
                        nodeIds = (flow["node_ids"] as? JsonArray).orEmpty().mapNotNull { entry ->
                            (entry as? JsonPrimitive)?.plainNativeString(512)
                        },
                        exit = key, decision = decision,
                        uploadedBytes = flow.nativeLong("upload_bytes") ?: 0,
                        downloadedBytes = flow.nativeLong("download_bytes") ?: 0,
                        active = flow.nativeBoolean("closed")?.not(),
                        startedAtMs = flow.nativeLong("started_ms"),
                        policyRevision = flow.nativeLong("revision"),
                        sourceIp = flow.nativeString("source_ip", 128),
                        sourcePort = flow.nativeInt("source_port")?.takeIf { it in 1..65535 },
                        destinationIp = flow.nativeString("destination_ip", 128),
                        destinationPort = flow.nativeInt("destination_port")?.takeIf { it in 1..65535 },
                        domain = flow.nativeString("domain", 4096),
                        processName = flow.nativeString("process_name", 4096),
                        network = flow.nativeString("network", 64),
                        sniffedProtocol = flow.nativeString("protocol", 64),
                        inspection = FlowInspection.fromNative(flow),
                        geoCountry = flow.nativeString("geo_country", 2),
                        observedAtMs = observedAtMs,
                        lastUpdateAtMs = flow.nativeLong("updated_ms")?.takeIf { it > 0 },
                        closedAtMs = flow.nativeLong("closed_ms")?.takeIf { it > 0 },
                        state = flow.nativeString("state", 64),
                        errorReason = flow.nativeString("error_reason", 64),
                        errorStage = flow.nativeString("error_stage", 64),
                        closeReason = flow.nativeString("close_reason", 64),
                    ),
                    actual.identity()
                )
            )
        }
        val observedTags = listOf("exits", "flows").flatMap { field ->
            (body[field] as? JsonArray).orEmpty().mapNotNull { item ->
                val value = item as? JsonObject ?: return@mapNotNull null
                value.nativeString(if (field == "exits") "tag" else "outbound")
            }
        }.toSet() + snapshot.config.exitTags.values
        synchronized(lifecycleLock) {
            if (applied === snapshot) tagBindings = tagBindings.filterKeys { it in observedTags }
        }
        return true
    }

    private fun requireApplied(expected: TunIdentity? = null): Applied {
        val current = requireNotNull(applied) { "Экспертный TUN не запущен" }
        check(expected == null || current.ack.identity() == expected) { "Операция относится к другому TUN" }
        return current
    }

    private fun isCurrent(owner: OwnedControl): Boolean =
        epoch.get() == owner.epoch && process === owner.process && client === owner.client && owner.process.isAlive

    private fun leaseFor(owned: Process): ProtectionLease {
        check(owned.isAlive) { "Собственное ядро уже остановлено" }
        return ProtectionLease(owned.pid(), owned.info().startInstant().orElseThrow().toEpochMilli())
    }

    private fun shutdownOwnedProcess(expectedOwner: OwnedControl? = null, restorePrevious: Boolean = false) {
        // Explicit Stop cancels preparation before waiting for the lifecycle lock. A late failed operation must not cancel a successor.
        if (expectedOwner == null) epoch.incrementAndGet()
        synchronized(lifecycleLock) {
            var cleanupEpoch = epoch.get()
            if (expectedOwner != null) {
                if (epoch.get() != expectedOwner.epoch || process !== expectedOwner.process || client !== expectedOwner.client) return
                cleanupEpoch = epoch.incrementAndGet()
            }
            polling?.cancel()
            val owned = process
            // A failed revocation never prevents terminating our core, but must not restore an unprotected mode.
            val revocationFailure = if (owned != null || revocationPending) runCatching(beforeStop).exceptionOrNull() else null
            // Core exit is not proof of guardian revocation. Preserve a failed operation
            // independently of the process reference so an explicit Stop can retry it.
            revocationPending = revocationFailure != null
            owned?.destroy()
            if (owned != null && !owned.waitFor(5, TimeUnit.SECONDS)) {
                owned.destroyForcibly()
                check(owned.waitFor(5, TimeUnit.SECONDS)) { "Windows не подтвердила остановку собственного ядра" }
            }
            if (process === owned) {
                process = null
                client = null
                applied = null
                tagBindings = emptyMap()
                controlPort = null
                negotiated = PolicyControlCapabilities.RESTART_ONLY
                negotiatedEpoch = null
                nativeNetworkEpoch = null
                underlayInterface = null
                if (reserved) {
                    simple.releaseExpertMode(revocationFailure == null && restorePrevious && epoch.get() == cleanupEpoch)
                    reserved = false
                }
            }
            if (revocationFailure != null) {
                throw ExpertStateUncertainException(
                    "Ядро остановлено, но Windows не подтвердила отзыв разрешения TUN. Системная защита сохранена.",
                    revocationFailure,
                )
            }
        }
    }

    private fun installCore(directory: Path): Path {
        BundledCore.install(directory)
        val expected = javaClass.getResourceAsStream("/runtime/lernet-core.exe.sha256")?.bufferedReader()?.use {
            it.readText().trim().substringBefore(' ').uppercase(Locale.ROOT)
        }
            ?: error("В сборке отсутствует контрольная сумма экспертного ядра")
        check(expected.matches(Regex("[0-9A-F]{64}"))) { "Некорректная контрольная сумма ядра" }
        val target = directory.resolve("lernet-core.exe")
        if (Files.isRegularFile(target) && checksum(target) == expected) return target
        val temporary = Files.createTempFile(directory, "core-", ".part")
        try {
            val source = javaClass.getResourceAsStream("/runtime/lernet-core.exe") ?: error("В сборке отсутствует экспертное ядро")
            source.use { Files.copy(it, temporary, StandardCopyOption.REPLACE_EXISTING) }
            check(checksum(temporary) == expected) { "Контрольная сумма экспертного ядра не совпала" }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return target
    }

    private fun checksum(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02X".format(it) }
    }

    private fun identityPayload(
        ack: ExpertNativeAck,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("instance_id", ack.instanceId)
        put("interface_id", ack.interfaceId)
        put("expected_revision", ack.revision)
        extra()
    }

    private fun ExpertNativeAck.identity() = TunIdentity(instanceId, interfaceId)

    private fun nativeReason(code: String): String = when (code) {
        "exit_sleeping" -> "Выход спит; проверка не пробуждает его"
        "https_probe_failed" -> "HTTPS-запрос через этот выход не получил ответа"
        "exit_unavailable" -> "Выход недоступен в текущей схеме"
        "exit_pending_flow_limit" -> "Очередь первого подключения заполнена"
        else -> expertNativeFailureExplanation(code)
    }
}

/** Protocol readiness alone does not prove that traffic still reaches the destination. */
internal fun nativeExitPhase(value: JsonObject): ExitPhase = ExpertNativeStatusEvidence.exitPhase(
    value.nativeString("phase", 64), value.nativeString("health", 64),
)

private fun JsonPrimitive.plainNativeString(maxLength: Int): String? =
    takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() && it.length <= maxLength && it.none(Char::isISOControl) }

private fun JsonObject.nativeString(field: String, maxLength: Int = 512): String? =
    (get(field) as? JsonPrimitive)?.plainNativeString(maxLength)

private fun JsonObject.nativeLong(field: String): Long? =
    (get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }

private fun JsonObject.nativeInt(field: String): Int? =
    (get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it >= 0 }

private fun JsonObject.nativeBoolean(field: String): Boolean? =
    (get(field) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
