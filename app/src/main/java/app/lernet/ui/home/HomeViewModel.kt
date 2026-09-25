package app.lernet.ui.home

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lernet.LerNetApp
import app.lernet.config.model.Group
import app.lernet.config.model.GroupLayout
import app.lernet.config.model.Profile
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.engine.ConnectionController
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.log.JournalCeiling
import app.lernet.engine.net.OutboundEndpoint
import app.lernet.engine.policy.ManualFailoverGroup
import app.lernet.engine.redact.LerNetLog
import app.lernet.settings.AppSettings
import app.lernet.settings.SettingsStore
import app.lernet.vpn.AndroidProtectedDialer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class HomeUiState(
    val snapshot: ConnectionSnapshot = ConnectionSnapshot.idle(),
    val settings: AppSettings = AppSettings(),
    val profiles: List<Profile> = emptyList(),
    val groups: List<Group> = emptyList(),
    val activeProfile: Profile? = null,
    val engineAvailable: Boolean = false,
    val applyRoutesPrompt: Boolean = false,
    val loading: Boolean = true,
    val pendingSwitch: Profile? = null,
    val pendingMode: RunMode? = null,
    val offerLastLogs: Boolean = false,
    val probes: Map<String, ProfileProbe> = emptyMap(),
)

data class ProfileProbe(val running: Boolean = false, val tcpMs: Long? = null, val reachable: Boolean = false)

sealed class HomeIntent {
    data object ToggleConnect : HomeIntent()

    data class SetMode(val mode: RunMode) : HomeIntent()

    data class SelectProfile(val id: String) : HomeIntent()

    data object ConfirmSwitch : HomeIntent()

    data object DismissSwitch : HomeIntent()

    data object ConfirmModeSwitch : HomeIntent()

    data object DismissModeSwitch : HomeIntent()

    data object DismissBanner : HomeIntent()

    data class DeleteProfile(val id: String) : HomeIntent()

    data object UndoDelete : HomeIntent()

    data class DuplicateProfile(val id: String) : HomeIntent()

    data class RenameProfile(val id: String, val name: String) : HomeIntent()

    data class CreateGroup(val name: String) : HomeIntent()

    data class RenameGroup(val id: String, val name: String) : HomeIntent()

    data class DeleteGroup(val id: String) : HomeIntent()

    data class SetGroupAutoFailover(val id: String, val enabled: Boolean) : HomeIntent()

    data class MoveProfile(val id: String, val groupId: String?, val index: Int) : HomeIntent()

    data class MoveGroup(val id: String, val index: Int) : HomeIntent()

    data class ReorderUngrouped(val id: String, val beforeId: String) : HomeIntent()

    data class ProbeProfiles(val ids: List<String>) : HomeIntent()

    data object ConfirmApplyRoutes : HomeIntent()

    data object DismissApplyRoutes : HomeIntent()

    data object DismissLastLogs : HomeIntent()

    data object SimulatePipeSilent : HomeIntent()
}

sealed class HomeEvent {
    data object RequestVpnPermission : HomeEvent()

    data class ProfileDeleted(val name: String) : HomeEvent()
}

private data class HomeOverlay(
    val pendingSwitchId: String? = null,
    val pendingMode: RunMode? = null,
    /** Mode chosen while connected; apply only after VPN consent. */
    val modeAwaitingVpn: RunMode? = null,
    val offerLastLogs: Boolean = false,
    val probes: Map<String, ProfileProbe> = emptyMap(),
)

private data class DeletedSnapshot(
    val profile: Profile,
    val nodes: List<RuleNodeRecord>,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: ConfigRepository,
    private val settingsStore: SettingsStore,
    private val controller: ConnectionController,
) : ViewModel() {
    val events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val transferMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun exportTransfer(uri: Uri, groupId: String? = null) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val raw = repository.exportTransfer(groupId, state.value.activeProfile?.id)
                    val stream = appContext.contentResolver.openOutputStream(uri, "wt") ?: error("Нет доступа к файлу")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(raw) }
                }
            }.onSuccess { transferMessages.emit("Архив LerNET сохранён") }
                .onFailure { transferMessages.emit("Экспорт не выполнен: ${it.message}") }
        }
    }

    fun importTransfer(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openInputStream(uri) ?: error("Нет доступа к файлу")
                    val bytes = stream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 20_000_000) { "Архив больше 20 МБ" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    repository.importTransfer(bytes.toString(Charsets.UTF_8), settingsStore.settings.first().defaultDnsPolicy)
                }
            }.onSuccess { bundle ->
                transferMessages.emit("Добавлено папок: ${bundle.groups.size}, профилей: ${bundle.profiles.size}, правил: ${bundle.rules.size}")
            }.onFailure { transferMessages.emit("Импорт не выполнен: ${it.message}") }
        }
    }

    private val overlay = MutableStateFlow(
        HomeOverlay(offerLastLogs = (appContext as LerNetApp).logStore.offerBanner),
    )
    private var lastDeleted: DeletedSnapshot? = null

    init {
        viewModelScope.launch {
            val legacy = settingsStore.settings.first()
            if (legacy.failoverEnabled && legacy.failoverGroupId != null) {
                repository.setGroupAutoFailover(legacy.failoverGroupId, true)
                settingsStore.setFailover(false, null)
            }
        }
        controller.setFailoverHandlers(
            resolve = { outboundId ->
                val profile = repository.profiles.first().firstOrNull { it.selectedOutboundId == outboundId }
                profile?.let {
                    val (ownerId, _) = repository.routingOwnerForProfile(it.id)
                    it to repository.ensureDefaultElse(ownerId)
                }
            },
            onSelected = { profileId -> settingsStore.setActiveProfile(profileId) },
        )
        viewModelScope.launch {
            val mb = settingsStore.settings.first().journalMaxMb
            (appContext as LerNetApp).logStore.applyJournalCap(JournalCeiling.bytes(mb))
        }
    }

    val state: StateFlow<HomeUiState> = combine(
        controller.snapshot,
        settingsStore.settings,
        repository.profiles,
        repository.groups,
        overlay,
    ) { snapshot, settings, profiles, groups, extra ->
        val active = profiles.firstOrNull { it.id == settings.activeProfileId } ?: profiles.firstOrNull()
        HomeUiState(
            snapshot = snapshot,
            settings = settings,
            profiles = profiles,
            groups = groups,
            activeProfile = active,
            engineAvailable = controller.engineAvailable,
            loading = false,
            pendingSwitch = extra.pendingSwitchId?.let { id -> profiles.firstOrNull { it.id == id } },
            pendingMode = extra.pendingMode,
            offerLastLogs = extra.offerLastLogs,
            probes = extra.probes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch {
            combine(settingsStore.settings, repository.groups, repository.profiles) { settings, groups, profiles ->
                Triple(settings, groups, profiles)
            }.collect { (settings, groups, profiles) ->
                val activeId = settings.activeProfileId ?: profiles.firstOrNull()?.id
                val group = groups.firstOrNull {
                    it.autoFailover && activeId in it.profileIds
                }
                val outboundIds = group?.profileIds.orEmpty().mapNotNull { profileId ->
                    profiles.firstOrNull { it.id == profileId }?.selectedOutboundId
                }
                val labels = profiles.associate { it.selectedOutboundId to it.name }
                controller.updateSettings(
                    reconnect = settings.reconnect,
                    failoverEnabled = group != null,
                    group = group?.let {
                        ManualFailoverGroup(
                            id = it.id,
                            name = it.name,
                            outboundIds = outboundIds,
                            labels = labels,
                        )
                    },
                )
            }
        }
    }

    fun refreshHop() {
        val current = state.value
        if (current.snapshot.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
            controller.refreshHop()
        } else {
            current.activeProfile?.selectedOutbound()?.singBoxJson
                ?.let(OutboundEndpoint::parse)
                ?.let(controller::previewHop)
        }
    }

    fun onIntent(intent: HomeIntent) {
        viewModelScope.launch {
            when (intent) {
                HomeIntent.ToggleConnect -> toggleConnect()
                is HomeIntent.SetMode -> requestMode(intent.mode)
                is HomeIntent.SelectProfile -> requestSelect(intent.id)
                HomeIntent.ConfirmSwitch -> confirmSwitch()
                HomeIntent.DismissSwitch -> overlay.update { it.copy(pendingSwitchId = null) }
                HomeIntent.ConfirmModeSwitch -> confirmModeSwitch()
                HomeIntent.DismissModeSwitch -> overlay.update { it.copy(pendingMode = null) }
                HomeIntent.DismissBanner -> {
                    val banner = state.value.snapshot.banner
                    if (banner != null) {
                        settingsStore.dismissBanner("${banner.fromName}->${banner.toName}")
                    }
                }
                is HomeIntent.DeleteProfile -> deleteProfile(intent.id)
                HomeIntent.UndoDelete -> restoreDeleted()
                is HomeIntent.DuplicateProfile -> {
                    val copyId = repository.duplicateProfile(intent.id)
                    val group = repository.groups.first().firstOrNull { intent.id in it.profileIds }
                    if (copyId != null && group != null) {
                        val groups = repository.groups.first()
                        repository.replaceAllMemberships(
                            GroupLayout.moveProfile(
                                groups, copyId, group.id,
                                group.profileIds.indexOf(intent.id) + 1
                            )
                        )
                    }
                }
                is HomeIntent.RenameProfile -> {
                    val name = intent.name.trim()
                    if (name.isNotEmpty()) {
                        repository.renameProfile(intent.id, name)
                    }
                }
                is HomeIntent.CreateGroup -> {
                    val name = intent.name.trim()
                    if (name.isNotEmpty()) repository.upsertGroup(name)
                }
                is HomeIntent.RenameGroup -> if (intent.name.isNotBlank()) {
                    repository.renameGroup(intent.id, intent.name)
                }
                is HomeIntent.DeleteGroup -> repository.deleteGroup(intent.id)
                is HomeIntent.SetGroupAutoFailover -> repository.setGroupAutoFailover(intent.id, intent.enabled)
                is HomeIntent.MoveProfile -> {
                    val groups = repository.groups.first()
                    if (intent.groupId == null) {
                        if (groups.any { intent.id in it.profileIds }) {
                            repository.replaceAllMemberships(groups.map { it.copy(profileIds = it.profileIds - intent.id) })
                        }
                        val ordered = repository.profiles.first().map { it.id }
                        val grouped = groups.flatMapTo(mutableSetOf()) { it.profileIds } - intent.id
                        repository.reorderProfiles(orderAfterUngroupedMove(ordered, grouped, intent.id, intent.index))
                    } else {
                        repository.replaceAllMemberships(
                            GroupLayout.moveProfile(groups, intent.id, intent.groupId, intent.index),
                        )
                    }
                }
                is HomeIntent.MoveGroup -> {
                    val groups = repository.groups.first()
                    repository.reorderGroups(GroupLayout.moveGroup(groups, intent.id, intent.index).map { it.id })
                }
                is HomeIntent.ReorderUngrouped -> {
                    val profiles = repository.profiles.first().map { it.id }.toMutableList()
                    if (intent.id in profiles && intent.beforeId in profiles && intent.id != intent.beforeId) {
                        profiles.remove(intent.id)
                        profiles.add(profiles.indexOf(intent.beforeId), intent.id)
                        repository.reorderProfiles(profiles)
                    }
                }
                is HomeIntent.ProbeProfiles -> probeProfiles(intent.ids)
                HomeIntent.ConfirmApplyRoutes -> reconnectSameOutbound()
                HomeIntent.DismissApplyRoutes -> Unit
                HomeIntent.DismissLastLogs -> {
                    (appContext as LerNetApp).logStore.clearPending()
                    overlay.update { it.copy(offerLastLogs = false) }
                }
                HomeIntent.SimulatePipeSilent -> controller.simulatePipeSilent()
            }
        }
    }

    private suspend fun probeProfiles(ids: List<String>) {
        val profiles = repository.profiles.first().associateBy { it.id }
        val unique = ids.distinct().filter { it in profiles }
        if (unique.isEmpty()) return
        overlay.update { old -> old.copy(probes = old.probes + unique.associateWith { ProfileProbe(running = true) }) }
        val slots = Semaphore(4)
        unique.map { id ->
            viewModelScope.async {
                slots.withPermit {
                    val endpoint = profiles[id]?.selectedOutbound()?.singBoxJson?.let(OutboundEndpoint::parse)
                    val result = if (endpoint == null) {
                        ProfileProbe(reachable = false)
                    } else {
                        val startedAt = System.nanoTime()
                        val ok = AndroidProtectedDialer().dial(endpoint, 3_000).isSuccess
                        ProfileProbe(
                            tcpMs = if (ok) ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1) else null,
                            reachable = ok
                        )
                    }
                    overlay.update { old -> old.copy(probes = old.probes + (id to result)) }
                }
            }
        }.awaitAll()
    }

    fun assignImportedToGroup(groupId: String, profileIds: List<String>) {
        viewModelScope.launch {
            val groups = repository.groups.first()
            var next = groups
            profileIds.forEach { profileId ->
                val group = next.firstOrNull { it.id == groupId } ?: return@launch
                next = app.lernet.config.model.GroupLayout.moveProfile(next, profileId, groupId, group.profileIds.size)
            }
            repository.replaceAllMemberships(next)
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            CrashTrail.mark("ui vpn permission granted=$granted")
            val awaiting = overlay.value.modeAwaitingVpn
            if (awaiting != null) {
                overlay.update { it.copy(modeAwaitingVpn = null) }
                if (granted) {
                    settingsStore.setMode(awaiting)
                    startEngine()
                }
                return@launch
            }
            if (!granted) {
                controller.onPermissionDenied()
                return@launch
            }
            startEngine()
        }
    }

    private suspend fun requestSelect(id: String) {
        val current = state.value
        if (current.activeProfile?.id == id) return
        if (isSessionActive(current.snapshot.state)) {
            overlay.update { it.copy(pendingSwitchId = id, pendingMode = null) }
            return
        }
        settingsStore.setActiveProfile(id)
    }

    private suspend fun requestMode(mode: RunMode) {
        val current = state.value
        if (current.settings.mode == mode) return
        if (isSessionActive(current.snapshot.state)) {
            overlay.update { it.copy(pendingMode = mode, pendingSwitchId = null) }
            return
        }
        settingsStore.setMode(mode)
    }

    private suspend fun confirmSwitch() {
        val pending = state.value.pendingSwitch ?: return
        overlay.update { it.copy(pendingSwitchId = null) }
        settingsStore.setActiveProfile(pending.id)
        startEngine()
    }

    private suspend fun confirmModeSwitch() {
        val pending = state.value.pendingMode ?: return
        overlay.update { it.copy(pendingMode = null) }
        if (pending == RunMode.FULL_VPN) {
            overlay.update { it.copy(modeAwaitingVpn = pending) }
            events.emit(HomeEvent.RequestVpnPermission)
            return
        }
        settingsStore.setMode(pending)
        startEngine()
    }

    private suspend fun deleteProfile(id: String) {
        val profile = repository.getProfile(id) ?: return
        val nodes = repository.listRuleNodes(id)
        lastDeleted = DeletedSnapshot(profile, nodes)
        repository.deleteProfile(id)
        events.emit(HomeEvent.ProfileDeleted(profile.name))
    }

    private suspend fun restoreDeleted() {
        val snapshot = lastDeleted ?: return
        lastDeleted = null
        repository.restoreProfile(snapshot.profile, snapshot.nodes)
    }

    private suspend fun toggleConnect() {
        val current = state.value
        if (isSessionActive(current.snapshot.state)) {
            LerNetLog.i(TAG, "ui disconnect state=${current.snapshot.state}")
            controller.disconnect()
            (appContext as LerNetApp).logStore.markClean()
            return
        }
        CrashTrail.mark("ui connect tap")
        CrashTrail.mark("ui connect mode=${current.settings.mode} profile=${current.activeProfile?.id}")
        LerNetLog.i(TAG, "ui connect mode=${current.settings.mode} profile=${current.activeProfile?.id}")
        if (current.settings.mode == RunMode.FULL_VPN) {
            events.emit(HomeEvent.RequestVpnPermission)
        } else {
            startEngine()
        }
    }

    private suspend fun startEngine() {
        (appContext as LerNetApp).logStore.armCrashWatch()
        val current = state.value
        val profile = current.activeProfile ?: return
        val (ownerId, _) = repository.routingOwnerForProfile(profile.id)
        val nodes = repository.ensureDefaultElse(ownerId)
        if (current.settings.activeProfileId == null) {
            settingsStore.setActiveProfile(profile.id)
        }
        CrashTrail.mark("ui startEngine profile=${profile.id} mode=${current.settings.mode} routes=$ownerId")
        LerNetLog.i(TAG, "ui startEngine profile=${profile.id} mode=${current.settings.mode} routes=$ownerId")
        controller.connect(
            profile, nodes, current.settings.mode, current.settings.logLevel,
            current.settings.engineDefaults
        )
    }

    private suspend fun reconnectSameOutbound() {
        startEngine()
    }
}

private const val TAG = "LerNet.Home"

internal fun isSessionActive(state: ConnectionState): Boolean =
    state == ConnectionState.CONNECTING ||
        state == ConnectionState.RECONNECTING ||
        state == ConnectionState.CONNECTED
