package app.lernet.engine.policy

/** What went wrong, as both platforms see it. */
enum class LinkFailure {
    /** Two tunnel checks in a row failed while the network was up. */
    TUNNEL_DEAD,

    /** The passive detector saw a known filter pattern ([BlockingDetector]). */
    BLOCKED,

    /** The core reported a dial, TLS or handshake error, or the session went silent. */
    ENGINE_ERROR,
}

enum class LinkDecision {
    /** No network at all: keep the VPN and the closed tunnel, burn no configs, wait. */
    WAIT_FOR_NETWORK,

    /** The network is there, the config is not: go to the next config of the folder now. */
    SWITCH_NOW,

    /** Not sure yet (network just changed, a single error): one quick retry of the same config. */
    RETRY_SAME,
}

/**
 * The «нет сети / умер конфиг» judge, shared by Android and Windows.
 *
 * - No network → wait. Every config would look dead, so none is marked dead and the folder is
 *   not walked; the session resumes as soon as the network is back.
 * - A network that appeared a moment ago gets [SETTLE_MS] before anything is blamed on the config.
 * - Network up and the tunnel check failed twice, or the block detector fired → the config is
 *   dead (or blocked): switch at once, without the slow retry burst on the same server.
 * - A core error is retried once on the same config, the second one switches.
 *
 * The OS "internet validated" flag is deliberately not used: in many networks the system check
 * host itself is unreachable while everything else works, so it would park a working VPN.
 */
object LinkJudge {
    const val SETTLE_MS = 4_000L
    const val QUICK_RETRY_MS = 1_000L

    fun decide(
        networkUp: Boolean,
        failure: LinkFailure,
        failuresOnConfig: Int,
        msSinceNetworkChange: Long,
        hasAlternative: Boolean,
    ): LinkDecision = when {
        !networkUp -> LinkDecision.WAIT_FOR_NETWORK
        msSinceNetworkChange in 0 until SETTLE_MS -> LinkDecision.RETRY_SAME
        !hasAlternative -> LinkDecision.RETRY_SAME
        failure == LinkFailure.BLOCKED || failure == LinkFailure.TUNNEL_DEAD -> LinkDecision.SWITCH_NOW
        failuresOnConfig >= 2 -> LinkDecision.SWITCH_NOW
        else -> LinkDecision.RETRY_SAME
    }

    /** Short honest status line for both platforms. */
    fun message(decision: LinkDecision): String = when (decision) {
        LinkDecision.WAIT_FOR_NETWORK -> "Нет сети · VPN держим, ждём, когда сеть вернётся"
        LinkDecision.SWITCH_NOW -> "Конфиг не отвечает при живой сети · переключаемся на следующий"
        LinkDecision.RETRY_SAME -> "Связь прервалась · пробуем ещё раз"
    }
}
