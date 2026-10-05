package app.lernet.desktop.expert

import app.lernet.routing.policy.NetworkPolicy

internal enum class ExpertDraftResponse { WAITING, CONFIRMED, FAILED }

/** A previous failure must not cancel acknowledgement tracking for a new retry. */
internal data class ExpertDraftSubmission(
    val policy: NetworkPolicy,
    private val previousError: String?,
    private val previousEventId: String?,
) {
    fun response(state: ExpertUiState): ExpertDraftResponse = when {
        state.draft == policy && state.error == null -> ExpertDraftResponse.CONFIRMED
        state.error != null &&
            (state.error != previousError || state.events.lastOrNull()?.id != previousEventId) -> ExpertDraftResponse.FAILED
        else -> ExpertDraftResponse.WAITING
    }

    companion object {
        fun capture(policy: NetworkPolicy, state: ExpertUiState): ExpertDraftSubmission =
            ExpertDraftSubmission(policy, state.error, state.events.lastOrNull()?.id)
    }
}
