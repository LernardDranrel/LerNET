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
    /** Parts of the imported config LerNET does not use, in plain Russian, for the import screen. */
    val unusedParts: List<String> = emptyList(),
)

sealed class ImportResult {
    data class Success(val drafts: List<ImportedProfileDraft>) : ImportResult() {
        /** All unused parts across drafts, without repeats. */
        val unusedParts: List<String> get() = drafts.flatMap { it.unusedParts }.distinct()
    }

    data class Failure(val errors: List<FieldError>) : ImportResult()
}
