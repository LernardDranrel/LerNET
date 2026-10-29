package app.lernet.desktop

import app.lernet.engine.policy.ExpertSessionPhase

/** Pure decisions for Expert underlay loss / return on Windows. */
internal object ExpertUnderlayRecovery {
    /** Start failed only because no physical adapter — retry when one appears. */
    fun shouldRetryFailedStart(
        desiredEnabled: Boolean,
        phase: ExpertSessionPhase,
        errors: List<String>,
    ): Boolean {
        if (!desiredEnabled || phase != ExpertSessionPhase.FAILED) return false
        return errors.any(::isMissingAdapterFailure)
    }

    fun isMissingAdapterFailure(message: String): Boolean {
        val text = message.lowercase()
        return "физического адаптера" in text ||
            "подключённого физического" in text ||
            "подключенного физического" in text ||
            "expert_underlay_unavailable" in text ||
            "expert_underlay_binding" in text
    }
}
