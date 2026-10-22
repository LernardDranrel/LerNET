package app.lernet.engine.policy

/** What went wrong, as both platforms see it. */
enum class LinkFailure {
    /** Two tunnel checks in a row failed while the network was up. */
    TUNNEL_DEAD,

    /** The passive detector saw a known filter pattern ([BlockingDetector]). Only a reason to check. */
    BLOCKED,

    /** The core itself stopped or could not start (dial, TLS, handshake error of the start). */
    ENGINE_ERROR,
}

enum class LinkDecision {
    /** No network at all: keep the VPN and the closed tunnel, burn no configs, wait. */
    WAIT_FOR_NETWORK,

    /**
     * The network is there, the config is not: look for a folder config that passes a real check.
     * The running core and its live connections stay until a replacement answered.
     */
    SWITCH_NOW,

    /** The core is gone: start the same config again after [RetryBackoff]. */
    RETRY_SAME,

    /** Slow or missing replies without proof of death: keep the running core, restart nothing. */
    HOLD,
}

/**
 * The «нет сети / умер конфиг» judge, shared by Android and Windows.
 *
 * - No network → wait. Every config would look dead, so none is marked dead and the folder is
 *   not walked; the session resumes as soon as the network is back.
 * - A network that appeared a moment ago gets [SETTLE_MS] before anything is blamed on the config.
 * - A restart never helps against slow or jammed replies: it only drops every live connection and
 *   costs new handshakes. So silence, missed DNS and the block detector hold the connection.
 * - Two failed tunnel checks (each up to [TunnelProbeSchedule.CHECK_TIMEOUT_MS]) mean the config is
 *   dead: with a folder, a replacement is looked for (and must pass a real check); without, hold.
 * - The core itself failing is retried on the same config; the second failure in a row switches
 *   when the folder has another config.
 *
 * The OS "internet validated" flag is deliberately not used: in many networks the system check
 * host itself is unreachable while everything else works, so it would park a working VPN.
 */
object LinkJudge {
    const val SETTLE_MS = 4_000L

    /** Rounds without any answer before the status says white lists / shutdown and checks get rare. */
    const val ALL_SILENT_ROUNDS = 3

    /** The silent round at which a core that may hold a broken pool is started once more. */
    const val STALE_CORE_ROUND = 2

    /** At most one such restart per this time. */
    const val STALE_CORE_GAP_MS = 10 * 60_000L

    /** Look again this soon when the tunnel failed right after the network changed. */
    const val SETTLE_RECHECK_MS = 15_000L

    /** Wait at most this long for a candidate server's handshake budget, else skip it this round. */
    const val CANDIDATE_BUDGET_WAIT_MS = 20_000L

    /** Pause between two folder candidates while looking for a replacement: one at a time. */
    fun candidateGapMs(random: kotlin.random.Random = kotlin.random.Random.Default): Long = random.nextLong(1_000L, 3_001L)

    fun decide(
        networkUp: Boolean,
        failure: LinkFailure,
        failuresOnConfig: Int,
        msSinceNetworkChange: Long,
        hasAlternative: Boolean,
    ): LinkDecision = when {
        !networkUp -> LinkDecision.WAIT_FOR_NETWORK
        failure == LinkFailure.BLOCKED -> LinkDecision.HOLD
        failure == LinkFailure.TUNNEL_DEAD && msSinceNetworkChange in 0 until SETTLE_MS -> LinkDecision.HOLD
        failure == LinkFailure.TUNNEL_DEAD -> if (hasAlternative) LinkDecision.SWITCH_NOW else LinkDecision.HOLD
        msSinceNetworkChange in 0 until SETTLE_MS -> LinkDecision.RETRY_SAME
        hasAlternative && failuresOnConfig >= 2 -> LinkDecision.SWITCH_NOW
        else -> LinkDecision.RETRY_SAME
    }

    /** Short honest status line for both platforms (plain mode, «вы»). */
    fun message(decision: LinkDecision): String = when (decision) {
        LinkDecision.WAIT_FOR_NETWORK -> "Нет сети · VPN держим, ждём, когда сеть вернётся"
        LinkDecision.SWITCH_NOW -> "Конфиг не отвечает при живой сети · проверяем запасные, соединение пока держим"
        LinkDecision.RETRY_SAME -> "Ядро остановилось · запускаем снова после паузы"
        LinkDecision.HOLD -> holdMessage(LinkHold.JAMMED)
    }

    /** Plain mode, «вы»: what the user sees while the connection is held without a restart. */
    fun holdMessage(hold: LinkHold, nextCheckLabel: String? = null): String = when (hold) {
        LinkHold.JAMMED -> "Связь глушат · держим соединение, ничего не перезапускаем"
        LinkHold.ALL_SILENT -> "Сеть есть, но ни один сервер не отвечает · возможно, оператор оставил только разрешённые сайты" +
            nextCheckLabel?.let { " · проверим снова через $it" }.orEmpty()
    }
}
