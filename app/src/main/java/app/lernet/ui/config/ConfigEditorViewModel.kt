package app.lernet.ui.config

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lernet.config.model.DnsPolicy
import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.repo.ConfigRepository
import app.lernet.config.repo.RuleNodeRecord
import app.lernet.engine.ConnectionController
import app.lernet.engine.RunMode
import app.lernet.engine.compile.ConfigTruth
import app.lernet.engine.compile.DnsDraft
import app.lernet.engine.compile.DnsServerDraft
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.compile.FieldView
import app.lernet.engine.compile.OutboundPatch
import app.lernet.engine.redact.LerNetLog
import app.lernet.engine.toRuleNode
import app.lernet.routing.CompiledRoute
import app.lernet.routing.RouteCompiler
import app.lernet.routing.RuleNode
import app.lernet.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConfigEditorUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val profileName: String = "",
    val server: String = "",
    val port: String = "",
    val sni: String = "",
    val path: String = "",
    val mode: String = "",
    val hasTransport: Boolean = false,
    val transportType: String = "",
    val dnsPolicy: DnsPolicy = DnsPolicy.UNDERLAY,
    val dnsServers: List<DnsServerDraft> = emptyList(),
    val fields: List<FieldView> = emptyList(),
    val engineJson: String = "",
    val fullEngineJson: String = "",
    val assembleError: String? = null,
    val fieldError: ConfigFieldError? = null,
    val confirmProfileDns: Boolean = false,
    val showEngine: Boolean = false,
    val dirty: Boolean = false,
    val saving: Boolean = false,
)

enum class ConfigFieldError {
    SERVER,
    PORT,
    DNS,
}

sealed class ConfigEditorIntent {
    data class Server(val value: String) : ConfigEditorIntent()

    data class Port(val value: String) : ConfigEditorIntent()

    data class Sni(val value: String) : ConfigEditorIntent()

    data class Path(val value: String) : ConfigEditorIntent()

    data class Mode(val value: String) : ConfigEditorIntent()

    data class DnsServer(val index: Int, val value: String) : ConfigEditorIntent()

    data class DnsType(val index: Int, val value: String) : ConfigEditorIntent()

    data object KeepSystemDns : ConfigEditorIntent()

    data object RequestProfileDns : ConfigEditorIntent()

    data object ConfirmProfileDns : ConfigEditorIntent()

    data object DismissProfileDns : ConfigEditorIntent()

    data object ToggleEngine : ConfigEditorIntent()

    data object Save : ConfigEditorIntent()
}

sealed class ConfigEditorEvent {
    data object Saved : ConfigEditorEvent()

    data object SaveFailed : ConfigEditorEvent()
}

@HiltViewModel
class ConfigEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ConfigRepository,
    private val settingsStore: SettingsStore,
    private val controller: ConnectionController,
) : ViewModel() {
    private val profileId: String = checkNotNull(savedStateHandle["profileId"])
    private val _state = MutableStateFlow(ConfigEditorUiState())
    val state: StateFlow<ConfigEditorUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ConfigEditorEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<ConfigEditorEvent> = _events.asSharedFlow()
    private var baseline: Baseline? = null
    private var savedDraft: DraftSnapshot? = null
    private var previewJob: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    fun onIntent(intent: ConfigEditorIntent) {
        when (intent) {
            is ConfigEditorIntent.Server -> edit { it.copy(server = intent.value, fieldError = null) }
            is ConfigEditorIntent.Port -> edit { it.copy(port = intent.value, fieldError = null) }
            is ConfigEditorIntent.Sni -> edit { it.copy(sni = intent.value, fieldError = null) }
            is ConfigEditorIntent.Path -> edit { it.copy(path = intent.value, fieldError = null) }
            is ConfigEditorIntent.Mode -> edit { it.copy(mode = intent.value, fieldError = null) }
            is ConfigEditorIntent.DnsServer -> editDns(intent.index) { it.copy(server = intent.value) }
            is ConfigEditorIntent.DnsType -> editDns(intent.index) { it.copy(type = intent.value) }
            ConfigEditorIntent.KeepSystemDns -> edit {
                it.copy(dnsPolicy = DnsPolicy.UNDERLAY, confirmProfileDns = false, fieldError = null)
            }
            ConfigEditorIntent.RequestProfileDns -> requestProfileDns()
            ConfigEditorIntent.ConfirmProfileDns -> confirmProfileDns()
            ConfigEditorIntent.DismissProfileDns -> _state.update { it.copy(confirmProfileDns = false) }
            ConfigEditorIntent.ToggleEngine -> _state.update { it.copy(showEngine = !it.showEngine) }
            ConfigEditorIntent.Save -> save()
        }
    }

    private suspend fun load() {
        val profile = repository.getProfile(profileId)
        val outbound = profile?.selectedOutbound()
        if (profile == null || outbound == null) {
            _state.update { it.copy(loading = false, missing = true) }
            return
        }
        val settings = settingsStore.settings.first()
        val (routingOwner, _) = repository.routingOwnerForProfile(profileId)
        val nodes = repository.ensureDefaultElse(routingOwner)
        val owned = OutboundPatch.read(outbound.singBoxJson)
        baseline = Baseline(
            outboundId = outbound.id,
            tag = outbound.tag,
            type = outbound.type,
            json = outbound.singBoxJson,
            dnsJson = profile.dnsJson,
            route = RouteCompiler.compile(nodes.toRouting()),
            mode = settings.mode,
            logLevel = settings.logLevel,
            defaults = settings.engineDefaults,
        )
        val loaded = ConfigEditorUiState(
            loading = false,
            profileName = profile.name,
            server = owned.server,
            port = owned.port,
            sni = owned.sni,
            path = owned.path,
            mode = owned.mode,
            hasTransport = owned.hasTransport,
            transportType = owned.transportType,
            dnsPolicy = profile.dnsPolicy,
            dnsServers = DnsDraft.read(profile.dnsJson),
        )
        savedDraft = DraftSnapshot.of(loaded)
        _state.value = withContext(Dispatchers.Default) { refresh(loaded) }
    }

    private fun edit(block: (ConfigEditorUiState) -> ConfigEditorUiState) {
        _state.update { current ->
            val next = block(current)
            next.copy(dirty = savedDraft?.let { DraftSnapshot.of(next) != it } ?: false)
        }
        schedulePreview()
    }

    private fun schedulePreview() {
        previewJob?.cancel()
        val draft = _state.value
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val rendered = withContext(Dispatchers.Default) { refresh(draft) }
            _state.update { current ->
                current.copy(
                    fields = rendered.fields,
                    engineJson = rendered.engineJson,
                    fullEngineJson = rendered.fullEngineJson,
                    assembleError = rendered.assembleError,
                )
            }
        }
    }

    private fun editDns(index: Int, block: (DnsServerDraft) -> DnsServerDraft) {
        edit { state ->
            state.copy(
                dnsServers = state.dnsServers.map { draft -> if (draft.index == index) block(draft) else draft },
                fieldError = null,
            )
        }
    }

    private fun requestProfileDns() {
        val current = _state.value
        if (current.dnsPolicy == DnsPolicy.PROFILE) return
        _state.update { it.copy(confirmProfileDns = true) }
    }

    private fun confirmProfileDns() {
        _state.update { current ->
            val seeded = current.dnsServers.ifEmpty {
                listOf(DnsServerDraft(index = 0, type = "udp", tag = "dns-remote", server = "", detour = ""))
            }
            current.copy(
                dnsPolicy = DnsPolicy.PROFILE,
                confirmProfileDns = false,
                dnsServers = seeded,
                fieldError = null,
                dirty = savedDraft?.let {
                    DraftSnapshot.of(current.copy(dnsPolicy = DnsPolicy.PROFILE, dnsServers = seeded)) != it
                } ?: false,
            )
        }
        schedulePreview()
    }

    private fun save() {
        val current = _state.value
        if (current.saving) return
        val error = validate(current)
        if (error != null) {
            _state.update { it.copy(fieldError = error) }
            return
        }
        val port = current.port.toInt()
        val base = baseline ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val (json, dns) = withContext(Dispatchers.Default) {
                    OutboundPatch.write(base.json, current.server, port, current.sni, current.path, current.mode) to
                        DnsDraft.write(base.dnsJson, current.dnsServers)
                }
                repository.saveEditor(profileId, base.outboundId, json, dns, current.dnsPolicy)
                baseline = base.copy(json = json, dnsJson = dns)
                savedDraft = DraftSnapshot.of(current)
                _state.update { it.copy(fieldError = null, dirty = DraftSnapshot.of(it) != savedDraft) }
                schedulePreview()
                _events.emit(ConfigEditorEvent.Saved)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LerNetLog.e("LerNet.ConfigEditor", "save failed", error)
                _events.emit(ConfigEditorEvent.SaveFailed)
            } finally {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    private fun validate(state: ConfigEditorUiState): ConfigFieldError? {
        if (state.server.isBlank()) return ConfigFieldError.SERVER
        val port = state.port.toIntOrNull()
        if (port == null || port !in 1..65535) return ConfigFieldError.PORT
        val missingDns = state.dnsPolicy == DnsPolicy.PROFILE &&
            state.dnsServers.any { DnsDraft.needsAddress(it) && it.server.isBlank() }
        if (missingDns) return ConfigFieldError.DNS
        return null
    }

    private fun refresh(state: ConfigEditorUiState): ConfigEditorUiState {
        val base = baseline ?: return state
        if (state.loading || state.missing) return state
        val port = state.port.toIntOrNull()
        if (state.server.isBlank() || port == null || port !in 1..65535) {
            return state.copy(
                engineJson = "", fullEngineJson = "",
                assembleError = "Укажите сервер и порт от 1 до 65535"
            )
        }
        if (state.dnsPolicy == DnsPolicy.PROFILE &&
            state.dnsServers.any { DnsDraft.needsAddress(it) && it.server.isBlank() }
        ) {
            return state.copy(
                engineJson = "", fullEngineJson = "",
                assembleError = "Укажите адрес DNS профиля"
            )
        }
        val json = OutboundPatch.write(base.json, state.server, port, state.sni, state.path, state.mode)
        val dns = DnsDraft.write(base.dnsJson, state.dnsServers)
        val outbound = NormalizedOutbound(base.outboundId, base.tag, base.type, json)
        val preview = ConfigTruth.preview(
            outbound = outbound,
            route = base.route,
            mode = base.mode,
            logLevel = base.logLevel,
            dnsJson = dns,
            dnsPolicy = state.dnsPolicy,
            defaults = base.defaults,
            ruleSetDirectory = controller.ruleSetDirectoryPath,
        )
        return state.copy(
            fields = preview.fields,
            engineJson = preview.engineJson,
            fullEngineJson = preview.fullEngineJson,
            assembleError = preview.errors.firstOrNull(),
        )
    }

    private fun List<RuleNodeRecord>.toRouting(): List<RuleNode> = map { it.toRuleNode() }

    private data class Baseline(
        val outboundId: String,
        val tag: String,
        val type: String,
        val json: String,
        val dnsJson: String?,
        val route: CompiledRoute,
        val mode: RunMode,
        val logLevel: String,
        val defaults: EngineDefaults,
    )

    private data class DraftSnapshot(
        val server: String,
        val port: String,
        val sni: String,
        val path: String,
        val mode: String,
        val dnsPolicy: DnsPolicy,
        val dnsServers: List<DnsServerDraft>,
    ) {
        companion object {
            fun of(state: ConfigEditorUiState) = DraftSnapshot(
                state.server,
                state.port,
                state.sni,
                state.path,
                state.mode,
                state.dnsPolicy,
                state.dnsServers,
            )
        }
    }

    private companion object {
        const val PREVIEW_DEBOUNCE_MS = 120L
    }
}
