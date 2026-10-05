package app.lernet.desktop

import app.lernet.config.policy.ExternalExitKind
import app.lernet.config.policy.ExternalExitProfiles
import app.lernet.config.policy.PolicyInventoryChange
import app.lernet.config.policy.PolicyMigration
import app.lernet.config.policy.PolicyWorkspaceRepository
import app.lernet.config.policy.PolicyWorkspaceStore
import app.lernet.config.redact.SecretRedactor
import app.lernet.config.transfer.TransferBundle
import app.lernet.config.transfer.TransferCodec
import app.lernet.desktop.expert.ExpertApplication
import app.lernet.desktop.expert.ExpertConnection
import app.lernet.desktop.expert.ExpertEvent
import app.lernet.desktop.expert.ExpertExit
import app.lernet.desktop.expert.ExpertExternalProfile
import app.lernet.desktop.expert.ExpertFolder
import app.lernet.desktop.expert.ExpertIntent as UiIntent
import app.lernet.desktop.expert.ExpertInterface
import app.lernet.desktop.expert.ExpertPhase
import app.lernet.desktop.expert.ExpertProfile
import app.lernet.desktop.expert.ExpertUiState
import app.lernet.desktop.protection.ProtectionIpc
import app.lernet.desktop.protection.ProtectionLease
import app.lernet.desktop.protection.ProtectionPlan
import app.lernet.desktop.protection.ProtectionRequest
import app.lernet.desktop.protection.ProtectionScope
import app.lernet.desktop.protection.ProtectionState
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.EnginePlatform
import app.lernet.engine.policy.ExitPhase
import app.lernet.engine.policy.ExpertExitKey
import app.lernet.engine.policy.ExpertIntent as RuntimeIntent
import app.lernet.engine.policy.ExpertPolicyPersistence
import app.lernet.engine.policy.ExpertRuntimeController
import app.lernet.engine.policy.ExpertRuntimeState
import app.lernet.engine.policy.ExpertSessionPhase
import app.lernet.engine.policy.PolicyConfigAssembler
import app.lernet.routing.GeoRuleSets
import app.lernet.routing.RoutePlatform
import app.lernet.routing.policy.NetworkPolicy
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The coordinator keeps durable workspace edits separate from the native acknowledged policy. */
internal class DesktopExpertController(private val simple: DesktopController) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val root = simple.workspaceDirectory()
    private val repository = PolicyWorkspaceRepository(PolicyWorkspaceStore(root.resolve("network-workspace.json")))
    private val mutable = MutableStateFlow(ExpertUiState(NetworkPolicy(), administrator = WindowsElevation.isElevated))
    val state = mutable
    private val closed = AtomicBoolean()
    private val epochOffsetMs = System.currentTimeMillis() - System.nanoTime() / 1_000_000

    @Volatile private var runtime: ExpertRuntimeController? = null

    @Volatile private var externalError: String? = null

    @Volatile private var guardEnabled = runCatching {
        Files.readString(root.resolve("expert-guard-enabled")) == "enabled"
    }.getOrDefault(false)

    @Volatile private var deferredInventory = false

    @Volatile private var automaticInventoryAllowed = true
    private var adoptedInventory: TransferBundle? = null

    @Volatile private var activeGuardPlan: ProtectionPlan? = null
    private val protection = WindowsExpertProtection()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val backend = WindowsExpertBackend(
        object : WindowsExpertOwner {
            override fun workspaceDirectory() = simple.workspaceDirectory()
            override fun reserveExpertMode() = simple.reserveExpertMode()
            override fun releaseExpertMode(restorePrevious: Boolean) = simple.releaseExpertMode(restorePrevious)
        },
        scope, assemble = { program ->
            val snapshot = repository.snapshot()
            val preferences = simple.state.value.saved
            val sets = prepareRuleSets()
            PolicyConfigAssembler.assemble(
                snapshot, program, EnginePlatform.WINDOWS,
                defaults = EngineDefaults(preferences.tunMtu, preferences.xmuxConcurrency, preferences.directDnsServer),
                ruleSetDirectory = sets.toString(), probeUrl = preferences.healthUrl,
                underlayInterface = WindowsPhysicalNetwork.select().name
            )
        }, probeUrl = { simple.state.value.saved.healthUrl }, log = ::writeLog, controlReady = { core, port ->
            if (guardEnabled) armGuard(core, port, null)
        }, prepareIngress = { core, tunName, capturedLease ->
            if (!guardEnabled) {
                null
            } else {
                val owner = protection.prepareOwnedTun(core.toString(), tunName, capturedLease()).getOrThrow()
                buildJsonObject {
                    put("pid", owner.pid)
                    put("started_at_ms", owner.startedAtMillis)
                    put("tun_name", owner.tunName)
                    put("luid", owner.luid)
                    put("if_index", owner.ifIndex)
                    put("guid", owner.guid)
                }
            }
        }, beforeStop = {
            if (guardEnabled) protection.revokeOwnedTun().getOrThrow()
        }, permitIngress = ::permitGuardIngress
    )

    init {
        simple.simpleModeRestriction = {
            if (guardEnabled || mutable.value.protection.systemGuardEnforced) {
                "Системная защита экспертного режима блокирует обычный VPN. " +
                    "Откройте «Экспертный режим → Защита» и восстановите сеть перед переключением."
            } else {
                null
            }
        }
        scope.launch {
            simple.state.map { it.saved }.distinctUntilChanged().collect {
                mutex.withLock { refreshInventory() }
            }
        }
        scope.launch {
            var previous: Set<String>? = null
            while (!closed.get()) {
                val current = runCatching {
                    val ownedIndex = backend.identity?.interfaceId?.split(':')?.getOrNull(1)?.toIntOrNull()
                    NetworkInterface.getNetworkInterfaces().toList().filter {
                        it.isUp &&
                            !it.isLoopback &&
                            it.index != ownedIndex &&
                            !it.name.contains("lernet", true) &&
                            !it.displayName.contains("lernet", true)
                    }
                        .flatMap { adapter ->
                            adapter.inetAddresses.toList().map { "${adapter.index}:${it.hostAddress}" }
                        }.toSet()
                }.getOrNull()
                if (current != null &&
                    previous != null &&
                    current != previous &&
                    runtime?.state?.value?.phase == ExpertSessionPhase.RUNNING
                ) {
                    runtime?.handle(RuntimeIntent.NetworkChanged)
                    runCatching { backend.networkChanged() }
                        .onFailure { showFailure("Не удалось уведомить ядро о смене сети", it) }
                }
                previous = current
                delay(3_000)
            }
        }
        refreshApplications()
        scope.launch {
            while (!closed.get()) {
                refreshProtection()
                // Guard readback is independent of healthy outbound probes and the TUN's status.
                delay(6_000)
            }
        }
    }

    private suspend fun refreshInventory(allowLiveApply: Boolean = true) {
        try {
            val previousRuntime = runtime?.state?.value
            if (previousRuntime?.applying == true ||
                previousRuntime?.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
            ) {
                deferredInventory = true
                return
            }
            val bundle = TransferCodec.decode(DesktopTransfer.export(simple.state.value.saved))
            val publishAddition = automaticInventoryAllowed &&
                PolicyInventoryChange.isAddition(adoptedInventory, bundle) &&
                previousRuntime?.phase == ExpertSessionPhase.RUNNING &&
                !previousRuntime.hasDraftChanges &&
                previousRuntime.appliedRevision == previousRuntime.saved.revision &&
                previousRuntime.appliedPolicy == previousRuntime.saved
            val workspace = repository.open(bundle)
            val current = runtime
            if (current == null) {
                val created = ExpertRuntimeController(
                    workspace.saved, PolicyMigration.inventory(bundle), RoutePlatform.WINDOWS, backend,
                    ExpertPolicyPersistence { saved, draft ->
                        repository.persist(saved, draft)
                        Unit
                    },
                    scope,
                    clockMs = { System.nanoTime() / 1_000_000 }, initialDraft = workspace.draft
                )
                runtime = created
                scope.launch {
                    created.state.collect { snapshot ->
                        present(snapshot)
                        if (deferredInventory &&
                            !snapshot.applying &&
                            snapshot.phase !in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
                        ) {
                            mutex.withLock {
                                if (deferredInventory) {
                                    deferredInventory = false
                                    refreshInventory()
                                }
                            }
                        }
                    }
                }
            } else if (current.state.value.applying ||
                current.state.value.phase in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
            ) {
                deferredInventory = true
            } else {
                current.handle(
                    RuntimeIntent.WorkspaceChanged(
                        workspace.saved, workspace.draft, PolicyMigration.inventory(bundle)
                    )
                )
                // Inventory additions are the only automatic policy publication. Draft edits still require Save/Apply.
                if (allowLiveApply &&
                    publishAddition &&
                    !current.state.value.hasDraftChanges &&
                    current.state.value.phase == ExpertSessionPhase.RUNNING &&
                    current.state.value.appliedRevision != workspace.saved.revision
                ) {
                    current.handle(RuntimeIntent.ApplySaved)
                }
            }
            adoptedInventory = bundle
            mutable.update {
                it.copy(
                    profiles = workspace.legacy.profiles.map { profile ->
                        ExpertProfile(
                            profile.id, profile.name, workspace.legacy.groups.firstOrNull { profile.id in it.profileIds }?.id,
                            profile.outbounds.firstOrNull { outbound -> outbound.id == profile.selectedOutboundId }?.type.orEmpty(),
                            interfaceBinding = ExternalExitProfiles.binding(profile)
                        )
                    },
                    folders = workspace.legacy.groups.map { group -> ExpertFolder(group.id, group.name) },
                    externalProfiles = workspace.legacy.profiles.mapNotNull { profile ->
                        ExternalExitProfiles.describe(profile)?.let { request ->
                            ExpertExternalProfile(profile.id, request, ExternalExitProfiles.fingerprint(profile))
                        }
                    }
                )
            }
        } catch (failure: Exception) {
            showFailure("Не удалось прочитать рабочую область", failure)
        }
    }

    fun dispatch(intent: UiIntent) {
        externalError = null
        if (intent is UiIntent.SaveExternalExit) {
            mutable.update {
                it.copy(
                    externalSavePending = intent.requestId, externalSaveError = null, externalSaveErrorRequestId = null
                )
            }
        }
        scope.launch {
            try {
                val controller = requireNotNull(runtime) { "Рабочая область ещё загружается" }
                when (intent) {
                    is UiIntent.SelectScope -> mutable.update { it.copy(selectedScope = intent.scope) }
                    is UiIntent.EditPolicy -> mutex.withLock { controller.handle(RuntimeIntent.Edit(intent.policy)) }
                    UiIntent.SaveDraft -> mutex.withLock { controller.handle(RuntimeIntent.SaveDraft) }
                    UiIntent.DiscardDraft -> mutex.withLock { controller.handle(RuntimeIntent.DiscardDraft) }
                    UiIntent.RestoreAppliedToDraft -> mutex.withLock { controller.handle(RuntimeIntent.RestoreAppliedToDraft) }
                    UiIntent.ApplySaved -> mutex.withLock {
                        controller.handle(RuntimeIntent.ApplySaved)
                        if (controller.state.value.appliedRevision == controller.state.value.saved.revision) {
                            automaticInventoryAllowed = true
                        }
                    }
                    UiIntent.Start -> mutex.withLock {
                        controller.handle(RuntimeIntent.Start)
                        if (controller.state.value.appliedRevision == controller.state.value.saved.revision) {
                            automaticInventoryAllowed = true
                        }
                        refreshProtection()
                    }
                    UiIntent.Stop -> {
                        controller.handle(RuntimeIntent.Stop)
                        refreshProtection()
                    }
                    UiIntent.Refresh -> {
                        mutex.withLock { refreshInventory() }
                        refreshApplications()
                        refreshProtection()
                    }
                    UiIntent.RefreshInterfaces -> refreshInterfaces()
                    is UiIntent.SaveExternalExit -> saveExternalExit(intent)
                    is UiIntent.WakeExit -> exitKey(intent.id)?.let { controller.handle(RuntimeIntent.WakeExit(it)) }
                    is UiIntent.SleepExit -> exitKey(intent.id)?.let { controller.handle(RuntimeIntent.SleepExit(it)) }
                    UiIntent.EnableSystemGuard -> error("Сначала подтвердите защиту всего устройства")
                    UiIntent.DisableSystemGuard, UiIntent.RecoverSystemGuard -> disableSystemGuard()
                    // Window actions are handled by the shell.
                    UiIntent.Import, UiIntent.Export, UiIntent.ChooseExecutable, UiIntent.OpenNetworkObservation -> Unit
                }
            } catch (failure: Exception) {
                if (intent is UiIntent.SaveExternalExit) {
                    mutable.update {
                        it.copy(
                            externalSavePending = null, externalSaveErrorRequestId = intent.requestId,
                            externalSaveError = SecretRedactor.redact(
                                failure.message ?: "Не удалось сохранить внешний выход"
                            ).take(600)
                        )
                    }
                } else {
                    showFailure("Действие не выполнено", failure)
                }
            }
        }
    }

    private suspend fun saveExternalExit(intent: UiIntent.SaveExternalExit) = mutex.withLock {
        if (mutable.value.externalSaveAck == intent.requestId) {
            mutable.update { it.copy(externalSavePending = null) }
            return@withLock
        }
        val active = requireNotNull(runtime)
        check(
            !active.state.value.applying &&
                active.state.value.phase !in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)
        ) {
            "Дождитесь завершения переключения или применения схемы"
        }
        refreshInventory(allowLiveApply = false)
        val beforeBundle = TransferCodec.decode(DesktopTransfer.export(simple.state.value.saved))
        val existing = intent.profileId?.let { id ->
            requireNotNull(beforeBundle.profiles.firstOrNull { it.id == id }) { "Профиль удалён" }
        }
        if (existing != null) {
            check(intent.expectedFingerprint == ExternalExitProfiles.fingerprint(existing)) {
                "Профиль изменился. Откройте его заново перед редактированием."
            }
        }
        if (intent.request.kind == ExternalExitKind.CORPORATE_INTERFACE) {
            val binding = requireNotNull(intent.request.binding)
            val unchanged = existing?.let(ExternalExitProfiles::binding) == ExternalExitProfiles.normalizeBinding(binding)
            if (!unchanged) {
                val actual = WindowsAdapterInventory.read(ownedInterfaceIndex()).firstOrNull {
                    it.binding == ExternalExitProfiles.normalizeBinding(binding)
                }
                check(actual?.eligible == true) {
                    "Выбранный интерфейс изменился или отключён. Обновите список и выберите его снова."
                }
            }
        }
        val profileId = existing?.id ?: UUID.randomUUID().toString()
        val outboundId = existing?.selectedOutboundId ?: UUID.randomUUID().toString()
        val profile = ExternalExitProfiles.build(intent.request, profileId, outboundId, existing)
        val afterBundle = beforeBundle.copy(
            profiles =
            if (existing == null) {
                beforeBundle.profiles + profile
            } else {
                beforeBundle.profiles.map {
                    if (it.id == profileId) profile else it
                }
            }
        )
        synchronized(simple) {
            check(TransferCodec.decode(DesktopTransfer.export(simple.state.value.saved)) == beforeBundle) {
                "Список профилей изменился. Повторите сохранение после обновления."
            }
            check(repository.snapshot().legacy == beforeBundle) {
                "Рабочая область не обновилась. Обновите список перед сохранением."
            }
            val plan = repository.previewInventory(afterBundle)
            repository.commitImported(plan) { updated -> simple.commitWorkspaceInventoryIfUnchanged(beforeBundle, updated) }
        }
        refreshInventory()
        mutable.update {
            it.copy(
                externalSavePending = null, externalSaveAck = intent.requestId, externalSaveError = null,
                externalSaveErrorRequestId = null,
                notice =
                if (existing == null) {
                    "Внешний выход добавлен. Выберите его в правиле."
                } else {
                    "Профиль сохранён. Примените сохранённую схему, чтобы изменить активный выход."
                }
            )
        }
    }

    private fun ownedInterfaceIndex(): Int? = backend.identity?.interfaceId?.split(':')?.getOrNull(1)?.toIntOrNull()

    private fun refreshInterfaces() {
        mutable.update { it.copy(interfacesLoading = true, interfacesNotice = null) }
        scope.launch {
            try {
                val adapters = WindowsAdapterInventory.read(ownedInterfaceIndex())
                mutable.update {
                    it.copy(
                        interfacesLoading = false,
                        interfaces = adapters.map { adapter ->
                            ExpertInterface(
                                adapter.binding,
                                adapter.binding.name + adapter.description.takeIf(String::isNotBlank)
                                    ?.let { value -> " · $value" }.orEmpty(),
                                adapter.status, adapter.eligible, adapter.reason
                            )
                        },
                        interfacesNotice = "Снимок интерфейсов Windows. " +
                            "Ядро повторно проверяет GUID, имя и номер перед каждым соединением; " +
                            "шифрование самого адаптера этим не доказывается."
                    )
                }
            } catch (failure: Exception) {
                mutable.update {
                    it.copy(
                        interfacesLoading = false, interfaces = emptyList(),
                        interfacesNotice = "Не удалось прочитать интерфейсы: " +
                            SecretRedactor.redact(failure.message.orEmpty()).take(250)
                    )
                }
            }
        }
    }

    fun enableSystemGuard() {
        scope.launch {
            try {
                mutex.withLock {
                    check(WindowsElevation.isElevated) { "Защите Windows нужны права администратора" }
                    check(
                        backend.identity == null &&
                            runtime?.state?.value?.phase !in setOf(
                                ExpertSessionPhase.RUNNING, ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING,
                            )
                    ) {
                        "Сначала остановите экспертный режим. " +
                            "Системная защита создаёт собственный адаптер при следующем включении."
                    }
                    backend.negotiateCapabilities()
                    val core = requireNotNull(backend.corePath)
                    val port = requireNotNull(backend.controlPort)
                    writeGuardPreference(true)
                    guardEnabled = true
                    armGuard(core, port, null)
                    refreshProtection()
                }
            } catch (failure: Exception) {
                showFailure("Не удалось включить защиту Windows", failure)
            }
        }
    }

    private fun permitGuardIngress(ack: ExpertNativeAck, capturedLease: () -> ProtectionLease) {
        if (!guardEnabled) return
        val luid = requireNotNull(ack.interfaceId.split(':').getOrNull(2)?.toLongOrNull()?.takeIf { it > 0 }) {
            "Ядро не подтвердило идентификатор адаптера для системной защиты"
        }
        armGuard(requireNotNull(backend.corePath), requireNotNull(backend.controlPort), luid, capturedLease())
    }

    private fun armGuard(core: Path, port: Int, luid: Long?, capturedLease: ProtectionLease? = null) {
        val gui = ProcessHandle.current().info().command().orElseThrow()
        val windows = System.getenv("SystemRoot") ?: "C:\\Windows"
        val plan = ProtectionPlan.build(
            ProtectionRequest(
                ProtectionScope.DEVICE, core.toString(), luid,
                ipc = listOf(ProtectionIpc(gui, port)), dhcpServicePath = "$windows\\System32\\svchost.exe",
                acceptedDeviceCoverage = true
            )
        )
        val lease: ProtectionLease? = if (luid == null) {
            null
        } else {
            capturedLease ?: requireNotNull(backend.processLease()) {
                "Собственное ядро не подтвердило идентичность процесса для разрешения TUN"
            }
        }
        protection.arm(plan, lease).getOrThrow()
        activeGuardPlan = plan
    }

    private suspend fun disableSystemGuard() = mutex.withLock {
        runtime?.handle(RuntimeIntent.Stop)
        check(backend.identity == null && runtime?.state?.value?.tun == null) {
            "Ядро не подтвердило остановку. Системная защита сохранена; повторите остановку перед восстановлением сети."
        }
        protection.recover().getOrThrow()
        writeGuardPreference(false)
        guardEnabled = false
        refreshProtection()
    }

    private fun writeGuardPreference(enabled: Boolean) {
        val target = root.resolve("expert-guard-enabled")
        val temporary = root.resolve("expert-guard-enabled.tmp")
        Files.writeString(temporary, if (enabled) "enabled" else "disabled")
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun refreshProtection() {
        val expected = activeGuardPlan?.let { plan ->
            if (backend.identity == null) plan.copy(filters = plan.filters.filter { it.persistent }) else plan
        }
        val inspected = protection.inspect(expected)
        mutable.update { before ->
            before.copy(
                protection = before.protection.copy(
                    systemGuardEnforced = inspected.getOrNull()?.state == ProtectionState.ACTIVE,
                    explanation = inspected.getOrNull()?.let { report ->
                        report.description + "\n" + report.limitations.joinToString("\n")
                    }
                        ?: (
                            "Windows не подтвердила системные фильтры. Права администратора или восстановление могут понадобиться; " +
                                "защита при сбое не доказана."
                            ),
                )
            )
        }
    }

    fun importArchive(raw: String, replace: Boolean = false) {
        scope.launch {
            mutex.withLock {
                try {
                    check(runtime?.state?.value?.phase !in setOf(ExpertSessionPhase.STARTING, ExpertSessionPhase.STOPPING)) {
                        "Дождитесь завершения переключения"
                    }
                    check(runtime?.state?.value?.applying != true) { "Дождитесь завершения применения схемы" }
                    refreshInventory(allowLiveApply = false)
                    synchronized(simple) {
                        val beforeBundle = TransferCodec.decode(DesktopTransfer.export(simple.state.value.saved))
                        check(repository.snapshot().legacy == beforeBundle) {
                            "Список профилей изменился. Обновите его перед импортом."
                        }
                        val plan = repository.previewImported(raw, replace)
                        repository.commitImported(plan) { updated ->
                            simple.commitWorkspaceInventoryIfUnchanged(beforeBundle, updated)
                        }
                    }
                    automaticInventoryAllowed = false
                    refreshInventory(allowLiveApply = false)
                    externalError = null
                    mutable.update {
                        it.copy(
                            notice = "Импортирована копия рабочей области. Проверьте схему перед применением.",
                            error = projectedRuntimeError(runtime?.state?.value)
                        )
                    }
                } catch (failure: kotlinx.serialization.SerializationException) {
                    showFailure("Импорт не завершён: проверьте формат архива LerNET", null)
                } catch (failure: Exception) {
                    showFailure("Импорт не завершён", failure)
                }
            }
        }
    }

    fun exportArchive(target: Path) {
        scope.launch {
            mutex.withLock {
                try {
                    val raw = repository.export()
                    Files.writeString(target, raw)
                    externalError = null
                    mutable.update {
                        it.copy(
                            notice = "Рабочая область сохранена: ${target.fileName}",
                            error = projectedRuntimeError(runtime?.state?.value)
                        )
                    }
                } catch (failure: Exception) {
                    showFailure("Не удалось сохранить архив", failure)
                }
            }
        }
    }

    fun addExecutable(path: String) {
        scope.launch {
            val file = Path.of(path).toFile()
            if (!file.isFile || !file.extension.equals("exe", true)) {
                showFailure("Выберите существующий EXE", null)
                return@launch
            }
            val icon = WindowsProcessIdentity.resolveObservation(file.absolutePath)?.icon
            mutable.update {
                it.copy(
                    applications = (
                        it.applications +
                            ExpertApplication(file.nameWithoutExtension, file.absolutePath, false, false, icon)
                        )
                        .distinctBy { app -> app.executable.lowercase() }
                )
            }
        }
    }

    private fun refreshApplications() {
        scope.launch {
            val applications = WindowsAppCatalog.load().map { app ->
                ExpertApplication(
                    app.label, app.executable.absolutePath, app.installed, app.running,
                    WindowsProcessIdentity.resolveObservation(app.executable.absolutePath)?.icon
                )
            }
            mutable.update { it.copy(applications = applications) }
        }
    }

    private fun present(snapshot: ExpertRuntimeState) {
        val config = backend.assembled
        val policy = snapshot.appliedPolicy
        val protected = policy?.let {
            (listOf(it.device) + it.trees).flatMap { tree -> tree.nodes }
                .filter { node -> node.protected }.map { node -> node.id }.toSet()
        }.orEmpty()
        val paths = snapshot.inactiveNodeIds
        mutable.update { previous ->
            previous.copy(
                saved = snapshot.saved, draft = snapshot.draft,
                applied = snapshot.appliedPolicy, appliedRevision = snapshot.appliedRevision,
                tunnelIdentity = snapshot.tun?.let { "${it.instanceId} · ${it.interfaceId}" },
                capabilities = snapshot.capabilities,
                phase = if (snapshot.applying) ExpertPhase.APPLYING else ExpertPhase.valueOf(snapshot.phase.name),
                busy = snapshot.applying,
                error = externalError ?: projectedRuntimeError(snapshot),
                inactiveReasons = paths.associateWith { id ->
                    if (snapshot.inactiveProtections.any { it.nodeId == id }) {
                        "Защита этой ветки здесь не действует: условие предназначено для другой платформы. " +
                            "Ветка сохранена и не выполняется в Windows."
                    } else {
                        "Эта ветка предназначена для другой платформы или выключена вместе с родителем. " +
                            "Она сохранена и не выполняется в Windows."
                    }
                },
                exits = snapshot.exits.map { exit ->
                    val physical = config?.exits?.firstOrNull { it.key == exit.key }
                    ExpertExit(
                        id = exitId(exit.key),
                        name = previous.profiles.firstOrNull { it.id == exit.key.profileId }?.name ?: exit.key.profileId,
                        targetDescription = (
                            exit.key.folderId?.let { id ->
                                "Папка: ${previous.folders.firstOrNull { it.id == id }?.name ?: id}"
                            } ?: "Отдельный профиль"
                            ) +
                            if (exit.key in snapshot.actualFolderSelections.values) " · выбран ядром" else "",
                        phase = exit.phase, profileId = exit.key.profileId, channelId = exit.key.channelId,
                        latencyMs = exit.latencyMs, activeFlows = exit.activeFlows, pendingFlows = exit.pendingFlows,
                        coldStart = physical?.lifecycle?.coldStart == true, reason = exit.reason,
                        lastCheck = exit.lastCheckMs?.let {
                            timeFormat.format(
                                Instant.ofEpochMilli(
                                    if (snapshot.capabilities.nativeHealthRecovery) it else it + epochOffsetMs
                                )
                            )
                        },
                        canWake = physical != null &&
                            exit.phase in setOf(ExitPhase.SLEEPING, ExitPhase.FAILED, ExitPhase.DEGRADED),
                        canSleep = physical != null &&
                            exit.activeFlows == 0 &&
                            exit.pendingFlows == 0 &&
                            exit.phase == ExitPhase.READY,
                        folderId = exit.key.folderId
                    )
                },
                connections = snapshot.connections.map { flow ->
                    ExpertConnection(
                        flow.id,
                        flow.application?.takeIf { it.isNotBlank() }?.let { path ->
                            WindowsProcessIdentity.resolveObservation(path)?.label ?: path
                        }
                            ?: "Владелец не определён ядром",
                        flow.destination, flow.protocol, flow.decision,
                        if (flow.nodeIds.isEmpty()) {
                            "Ядро не передало путь по узлам для этого соединения."
                        } else {
                            "Ядро сопоставило соединение с ${flow.nodeIds.size} узлами " +
                                "версии ${flow.policyRevision ?: "не определена"}."
                        },
                        flow.nodeIds, flow.exit?.let(::exitId), flow.uploadedBytes, flow.downloadedBytes, flow.active,
                        flow.policyRevision == policy?.revision && flow.nodeIds.any { it in protected },
                        policyRevision = flow.policyRevision
                    )
                },
                events = snapshot.reasons.map { event ->
                    ExpertEvent(
                        event.sequence.toString(), timeFormat.format(Instant.ofEpochMilli(epochOffsetMs + event.atMs)),
                        event.message, event.message, event.level.name != "INFO"
                    )
                },
                protection = previous.protection.copy(
                    exitFailureEnforced = snapshot.phase == ExpertSessionPhase.RUNNING && snapshot.appliedRevision != null,
                    dnsFollowsPolicy = config?.dnsSafetyErrors?.isEmpty() == true && snapshot.phase == ExpertSessionPhase.RUNNING,
                    ipv6Covered = config != null && snapshot.phase == ExpertSessionPhase.RUNNING
                ),
            )
        }
    }

    private fun exitKey(id: String) = runtime?.state?.value?.exits?.firstOrNull { exitId(it.key) == id }?.key
    private fun exitId(key: ExpertExitKey): String =
        listOf(key.profileId, key.folderId.orEmpty(), key.channelPath.joinToString("/")).joinToString("|")

    private fun prepareRuleSets(): Path {
        val directory = root.resolve("rule-set")
        Files.createDirectories(directory)
        GeoRuleSets.bundled.forEach { country ->
            val name = GeoRuleSets.fileName(country)
            val file = directory.resolve(name)
            if (!Files.isRegularFile(file)) {
                val resource = javaClass.getResourceAsStream("/rule-set/$name") ?: error("В комплекте отсутствует $name")
                resource.use { Files.copy(it, file) }
            }
        }
        return directory
    }

    private fun showFailure(prefix: String, failure: Throwable?) {
        val message = "$prefix${failure?.message?.let { ": ${SecretRedactor.redact(it).take(500)}" }.orEmpty()}"
        externalError = message
        mutable.update { it.copy(error = message) }
        writeLog(message)
    }

    fun reportError(message: String) = showFailure(message, null)

    private fun writeLog(message: String) {
        synchronized(root) {
            val file = root.resolve("expert.log")
            if (Files.exists(file) && Files.size(file) > 5_000_000) {
                Files.move(file, root.resolve("expert.log.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(
                file, "${Instant.now()} ${SecretRedactor.redact(message)}\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runBlocking(Dispatchers.IO) { backend.stop(null) }
        scope.cancel()
        // Explicit OS recovery is separate: closing a window must not silently remove leak protection.
    }
}

/** Window actions may clear their own error, never acknowledge an unwritten runtime draft. */
internal fun projectedRuntimeError(snapshot: ExpertRuntimeState?): String? = snapshot?.let {
    (it.errors + it.draftErrors + listOfNotNull(it.draftPersistenceError))
        .distinct().takeIf { messages -> messages.isNotEmpty() }?.joinToString("\n")
}
