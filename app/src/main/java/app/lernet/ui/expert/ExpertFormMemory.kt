package app.lernet.ui.expert

import androidx.compose.runtime.mutableStateOf
import app.lernet.config.transfer.TransferProfile
import app.lernet.routing.policy.NetworkPolicy
import app.lernet.routing.policy.PolicyRoutePreview

/** ViewModel-owned state. Secrets stay in memory, never in Android's saved-instance Bundle. */
internal class ExpertFormMemory {
    var externalProfile: TransferProfile? = null
    val passwords = mutableMapOf<String, androidx.compose.runtime.MutableState<String>>()
    val preview = mutableStateOf<Pair<NetworkPolicy, PolicyRoutePreview>?>(null)
    var simulationInputs: List<Any?>? = null
}
