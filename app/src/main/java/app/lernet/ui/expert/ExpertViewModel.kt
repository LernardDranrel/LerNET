package app.lernet.ui.expert

import android.net.Uri
import androidx.lifecycle.ViewModel
import app.lernet.config.policy.ExternalExitRequest
import app.lernet.config.transfer.TransferProfile
import app.lernet.engine.policy.ExpertIntent
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyDnsSettings
import app.lernet.routing.policy.PolicyHealthSettings
import app.lernet.ui.controls.TemplateAction
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ExpertViewModel @Inject constructor(private val coordinator: ExpertCoordinator) : ViewModel() {
    private var formTemplateId: String? = null
    private var forms = ExpertFormMemory()
    internal fun formMemory(templateId: String): ExpertFormMemory {
        if (formTemplateId != templateId) {
            forms = ExpertFormMemory()
            formTemplateId = templateId
        }
        return forms
    }
    val state = coordinator.state
    val messages = coordinator.messages
    fun connectedVpnProfileId(): String? = coordinator.connectedVpnProfileId()
    fun startKeepingVpn(templateId: String, profileId: String, expected: NetworkPolicy) =
        coordinator.startKeepingVpn(templateId, profileId, expected)
    fun onIntent(intent: ExpertIntent, templateId: String) = coordinator.dispatch(intent, templateId)
    suspend fun changeTemplate(templateId: String, action: TemplateAction): String? = coordinator.changeTemplate(templateId, action)
    suspend fun saveDraft(templateId: String, expected: NetworkPolicy, next: NetworkPolicy): String? = coordinator.saveDraft(templateId, expected, next)
    fun export(uri: Uri) = coordinator.export(uri)
    fun import(uri: Uri, replace: Boolean = false) = coordinator.import(uri, replace)
    fun refreshEnvironment() = coordinator.refreshEnvironment()
    suspend fun saveExternalExit(request: ExternalExitRequest, expected: TransferProfile?): String? =
        coordinator.saveExternalExit(request, expected)
    suspend fun saveDnsSettings(templateId: String, expected: PolicyDnsSettings, next: PolicyDnsSettings): String? =
        coordinator.saveDnsSettings(templateId, expected, next)

    suspend fun saveHealthSettings(templateId: String, expected: PolicyHealthSettings, next: PolicyHealthSettings): String? =
        coordinator.saveHealthSettings(templateId, expected, next)

    suspend fun copyProfile(profileId: String) = coordinator.copyProfile(profileId)

    suspend fun renameFolder(id: String, name: String) = coordinator.renameFolder(id, name)

    suspend fun deleteFolder(id: String) = coordinator.deleteFolder(id)

    suspend fun refreshSubscription(url: String): String = coordinator.refreshSubscription(url)
}
