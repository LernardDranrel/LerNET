package app.lernet.engine.policy

enum class DnsHealthPhase {
    WaitingFirstOk,
    Healthy,
    RecoveringFromFail,
}

enum class DnsHealthAction {
    Hold,
    SoftReload,
    Fail,
}

/**
 * DNS must come up after Connected. TUN bytes / TCP dials are not a substitute.
 * After the first ok, idle is allowed; a later `dns query fail` starts recovery.
 */
class DnsHealthGate(
    val deadlineMs: Long = DEADLINE_MS,
) {
    var phase: DnsHealthPhase = DnsHealthPhase.WaitingFirstOk
        private set
    var reloadsUsed: Int = 0
        private set

    fun resetForConnect() {
        phase = DnsHealthPhase.WaitingFirstOk
        reloadsUsed = 0
    }

    fun resetPhaseForReload() {
        phase = DnsHealthPhase.WaitingFirstOk
    }

    fun onDnsOk() {
        phase = DnsHealthPhase.Healthy
    }

    fun onDnsFail() {
        if (phase == DnsHealthPhase.Healthy) {
            phase = DnsHealthPhase.RecoveringFromFail
        }
    }

    @Suppress("unused")
    fun onTrafficBytes() = Unit

    fun onDeadline(): DnsHealthAction =
        when (phase) {
            DnsHealthPhase.Healthy -> DnsHealthAction.Hold
            DnsHealthPhase.WaitingFirstOk,
            DnsHealthPhase.RecoveringFromFail,
            ->
                if (reloadsUsed == 0) {
                    reloadsUsed += 1
                    DnsHealthAction.SoftReload
                } else {
                    DnsHealthAction.Fail
                }
        }

    companion object {
        const val DEADLINE_MS = 8_000L
    }
}
