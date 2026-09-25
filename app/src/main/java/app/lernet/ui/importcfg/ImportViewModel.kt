package app.lernet.ui.importcfg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lernet.config.parse.FieldError
import app.lernet.config.parse.GuessedMode
import app.lernet.config.parse.ImportCoordinator
import app.lernet.config.parse.ImportGuess
import app.lernet.config.parse.ImportHint
import app.lernet.config.parse.ImportResult
import app.lernet.config.repo.ConfigRepository
import app.lernet.engine.redact.LerNetLog
import app.lernet.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImportMode {
    VLESS,
    JSON_URL,
    JSON_PASTE,
    SUBSCRIPTION,
}

data class ImportUiState(
    val modeOverride: ImportMode? = null,
    val guess: ImportGuess = ImportGuess.EMPTY,
    val input: String = "",
    val displayName: String = "",
    val errors: List<FieldError> = emptyList(),
    val busy: Boolean = false,
    val savedCount: Int = 0,
    val done: Boolean = false,
    val firstSavedId: String? = null,
) {
    val effectiveMode: ImportMode? get() = modeOverride ?: guess.mode?.toImportMode()
}

sealed class ImportIntent {
    data class SetMode(val mode: ImportMode) : ImportIntent()

    data class SetInput(val value: String) : ImportIntent()

    data class SetDisplayName(val value: String) : ImportIntent()

    data object Submit : ImportIntent()
}

sealed class ImportEvent {
    data class Saved(val count: Int, val firstProfileId: String?, val profileIds: List<String>) : ImportEvent()
}

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val coordinator: ImportCoordinator,
    private val repository: ConfigRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()
    val events = MutableSharedFlow<ImportEvent>(extraBufferCapacity = 4)
    private var classifyJob: Job? = null

    fun onIntent(intent: ImportIntent) {
        if (_state.value.busy && intent != ImportIntent.Submit) return
        when (intent) {
            is ImportIntent.SetMode ->
                _state.update { it.copy(modeOverride = intent.mode, errors = emptyList(), done = false) }
            is ImportIntent.SetInput -> onInput(intent.value)
            is ImportIntent.SetDisplayName -> _state.update { it.copy(displayName = intent.value, done = false) }
            ImportIntent.Submit -> submit()
        }
    }

    private fun onInput(value: String) {
        _state.update { it.copy(input = value, guess = ImportGuess.EMPTY, errors = emptyList(), done = false) }
        classifyJob?.cancel()
        classifyJob = viewModelScope.launch {
            delay(IMPORT_CLASSIFY_DEBOUNCE_MS)
            try {
                val guess = withContext(Dispatchers.Default) { ImportHint.detect(value) }
                if (_state.value.input != value) return@launch
                _state.update { it.copy(guess = guess) }
                if (guess.needsFetch) {
                    val refined = withContext(Dispatchers.IO) { coordinator.classifyUrl(value.trim()) }
                    if (_state.value.input == value) _state.update { it.copy(guess = refined) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LerNetLog.w("LerNet.Import", "classification failed: ${error.message}")
            }
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.busy) return
        val mode = current.effectiveMode ?: return
        classifyJob?.cancel()
        _state.update { it.copy(busy = true, errors = emptyList(), done = false) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { import(mode, current.input) }
                when (result) {
                    is ImportResult.Failure ->
                        _state.update { it.copy(errors = result.errors) }
                    is ImportResult.Success -> save(current, result)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                LerNetLog.e("LerNet.Import", "import failed", error)
                _state.update {
                    it.copy(errors = listOf(FieldError("input", "Не удалось импортировать. Повторите попытку.")))
                }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun save(current: ImportUiState, result: ImportResult.Success) {
        val named = withContext(Dispatchers.Default) {
            coordinator.withDisplayName(result, current.displayName) as ImportResult.Success
        }
        val defaultDnsPolicy = settingsStore.settings.first().defaultDnsPolicy
        val ids = withContext(Dispatchers.IO) { repository.importDrafts(named.drafts, defaultDnsPolicy) }
        if (ids.isNotEmpty()) settingsStore.setActiveProfile(ids.first())
        _state.update {
            it.copy(
                busy = false,
                savedCount = ids.size,
                done = true,
                input = "",
                guess = ImportGuess.EMPTY,
                firstSavedId = ids.firstOrNull(),
            )
        }
        events.emit(ImportEvent.Saved(ids.size, ids.firstOrNull(), ids))
    }

    private fun import(mode: ImportMode, input: String): ImportResult = when (mode) {
        ImportMode.VLESS -> coordinator.importVless(input)
        ImportMode.JSON_URL -> coordinator.importJsonUrl(input)
        ImportMode.JSON_PASTE -> coordinator.importPastedJson(input)
        ImportMode.SUBSCRIPTION ->
            if (ImportHint.isHttpUrl(input)) {
                coordinator.importSubscription(input)
            } else {
                coordinator.importSubscriptionDocument(input)
            }
    }
}

private const val IMPORT_CLASSIFY_DEBOUNCE_MS = 250L

private fun GuessedMode.toImportMode(): ImportMode = when (this) {
    GuessedMode.VLESS -> ImportMode.VLESS
    GuessedMode.JSON_PASTE -> ImportMode.JSON_PASTE
    GuessedMode.JSON_URL -> ImportMode.JSON_URL
    GuessedMode.SUBSCRIPTION -> ImportMode.SUBSCRIPTION
}
