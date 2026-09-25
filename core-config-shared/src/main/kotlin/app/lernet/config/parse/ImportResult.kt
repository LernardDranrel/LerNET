package app.lernet.config.parse

import app.lernet.config.model.NormalizedOutbound
import app.lernet.config.model.ProfileSource

data class ImportedProfileDraft(
    val name: String,
    val source: ProfileSource,
    val outbounds: List<NormalizedOutbound>,
    val selectedOutboundId: String,
    val subscriptionUrl: String?,
    val dnsJson: String? = null,
)

sealed class ImportResult {
    data class Success(val drafts: List<ImportedProfileDraft>) : ImportResult()

    data class Failure(val errors: List<FieldError>) : ImportResult()
}
