package app.lernet.ui.expert

import android.net.Uri
import androidx.lifecycle.ViewModel
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.ExpertIntent
import app.lernet.routing.policy.PolicyHealthSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ExpertViewModel @Inject constructor(private val coordinator: ExpertCoordinator) : ViewModel() {
    val state = coordinator.state
    val messages = coordinator.messages
    fun onIntent(intent: ExpertIntent) = coordinator.dispatch(intent)
    fun export(uri: Uri) = coordinator.export(uri)
    fun import(uri: Uri, replace: Boolean = false) = coordinator.import(uri, replace)
    fun refreshEnvironment() = coordinator.refreshEnvironment()
    suspend fun saveExternalExit(request: ExternalExitRequest, expected: TransferProfile?): String? =
        coordinator.saveExternalExit(request, expected)
    suspend fun saveHealthSettings(expected: PolicyHealthSettings, next: PolicyHealthSettings): String? =
        coordinator.saveHealthSettings(expected, next)
}
