package app.lernet.ui.expert

import android.net.Uri
import androidx.lifecycle.ViewModel
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.ExpertIntent
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyDnsSettings
import app.lernet.routing.policy.PolicyHealthSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ExpertViewModel @Inject constructor(private val coordinator: ExpertCoordinator) : ViewModel() {
    internal val formMemory = ExpertFormMemory()
    val state = coordinator.state
    val messages = coordinator.messages
    fun connectedVpnProfileId(): String? = coordinator.connectedVpnProfileId()
    fun startKeepingVpn(profileId: String, expected: NetworkPolicy) =
        coordinator.startKeepingVpn(profileId, expected)
    fun onIntent(intent: ExpertIntent) = coordinator.dispatch(intent)
    suspend fun saveDraft(expected: NetworkPolicy, next: NetworkPolicy): String? = coordinator.saveDraft(expected, next)
    fun export(uri: Uri) = coordinator.export(uri)
    fun import(uri: Uri, replace: Boolean = false) = coordinator.import(uri, replace)
    fun refreshEnvironment() = coordinator.refreshEnvironment()
    suspend fun saveExternalExit(request: ExternalExitRequest, expected: TransferProfile?): String? =
        coordinator.saveExternalExit(request, expected)
    suspend fun saveDnsSettings(expected: PolicyDnsSettings, next: PolicyDnsSettings): String? =
        coordinator.saveDnsSettings(expected, next)

    suspend fun saveHealthSettings(expected: PolicyHealthSettings, next: PolicyHealthSettings): String? =
        coordinator.saveHealthSettings(expected, next)
}
