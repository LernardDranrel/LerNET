package app.lernet.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import app.lernet.BuildConfig
import androidx.lifecycle.viewModelScope
import app.lernet.LerNetApp
import app.lernet.config.model.DnsPolicy
import app.lernet.engine.ConnectionController
import app.lernet.engine.compile.EngineDefaults
import app.lernet.engine.log.JournalCeiling
import app.lernet.settings.AppSettings
import app.lernet.settings.SettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val engineVersion: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val pendingJournalMb: Int? = null,
)

sealed class SettingsIntent {
    data class SetMaxAttempts(val value: Int) : SettingsIntent()

    data class SetWatchdogSeconds(val value: Int) : SettingsIntent()

    data class SetBackoffCapSeconds(val value: Int) : SettingsIntent()

    data class SetLogLevel(val level: String) : SettingsIntent()

    data class SetTunMtu(val value: Int) : SettingsIntent()

    data class SetXmuxConcurrency(val value: String) : SettingsIntent()

    data class SetDefaultDnsPolicy(val value: DnsPolicy) : SettingsIntent()

    data class SetDirectDnsServer(val value: String) : SettingsIntent()

    data class ProposeJournal(val mb: Int) : SettingsIntent()

    data object ConfirmJournal : SettingsIntent()

    data object DismissJournal : SettingsIntent()

    data object SimulatePipeSilent : SettingsIntent()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val controller: ConnectionController,
) : ViewModel() {
    private val pendingJournal = MutableStateFlow<Int?>(null)

    val state: StateFlow<SettingsUiState> = combine(
        settingsStore.settings,
        pendingJournal,
    ) { settings, pending ->
        SettingsUiState(settings, controller.engineVersion, pendingJournalMb = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onIntent(intent: SettingsIntent) {
        viewModelScope.launch {
            val current = state.value.settings
            when (intent) {
                is SettingsIntent.SetMaxAttempts ->
                    settingsStore.setReconnect(current.reconnect.copy(maxAttempts = intent.value.coerceIn(1, 20)))
                is SettingsIntent.SetWatchdogSeconds ->
                    settingsStore.setReconnect(
                        current.reconnect.copy(watchdogTimeoutMs = intent.value.coerceIn(5, 120) * 1000L),
                    )
                is SettingsIntent.SetBackoffCapSeconds ->
                    settingsStore.setReconnect(
                        current.reconnect.copy(backoffCapMs = intent.value.coerceIn(2, 120) * 1000L),
                    )
                is SettingsIntent.SetLogLevel -> settingsStore.setLogLevel(intent.level)
                is SettingsIntent.SetTunMtu -> settingsStore.setEngineDefaults(
                    current.engineDefaults.copy(tunMtu = intent.value.coerceIn(1280, 9000))
                )
                is SettingsIntent.SetXmuxConcurrency -> if (intent.value in setOf("1-1", "8-8", "16-16", "32-32")) {
                    settingsStore.setEngineDefaults(current.engineDefaults.copy(xmuxConcurrency = intent.value))
                }
                is SettingsIntent.SetDefaultDnsPolicy -> settingsStore.setDefaultDnsPolicy(intent.value)
                is SettingsIntent.SetDirectDnsServer -> if (EngineDefaults.validIpv4(intent.value)) {
                    settingsStore.setEngineDefaults(current.engineDefaults.copy(directDnsServer = intent.value))
                }
                is SettingsIntent.ProposeJournal -> pendingJournal.value = JournalCeiling.mb(intent.mb)
                SettingsIntent.ConfirmJournal -> confirmJournal()
                SettingsIntent.DismissJournal -> pendingJournal.value = null
                SettingsIntent.SimulatePipeSilent -> controller.simulatePipeSilent()
            }
        }
    }

    private suspend fun confirmJournal() {
        val mb = pendingJournal.value ?: return
        settingsStore.setJournalMaxMb(mb)
        withContext(Dispatchers.IO) {
            (appContext as LerNetApp).logStore.applyJournalCap(JournalCeiling.bytes(mb))
        }
        pendingJournal.value = null
    }
}
