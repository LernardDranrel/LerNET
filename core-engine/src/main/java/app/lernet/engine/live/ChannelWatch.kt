package app.lernet.engine.live

enum class SilenceReport {
    NONE,
    PARTIAL,
    ALL,
}

enum class ChannelHealth {
    UNKNOWN,
    HOP_UP,
    HOP_DOWN,
    HOP_LOST,
    DATA_STALLED,

    /** Hop and/or DNS look fine, but requests entered the tunnel and did not finish. */
    PIPE_SILENT,

    /** Every recent tunnel-bound request got no reply. */
    TUNNEL_DEAD,
}

object ChannelWatch {
    fun afterProbe(wasUp: Boolean, ok: Boolean): ChannelHealth = when {
        ok -> ChannelHealth.HOP_UP
        wasUp -> ChannelHealth.HOP_LOST
        else -> ChannelHealth.HOP_DOWN
    }

    /**
     * Null means the window still needs the one extra hop probe.
     * [requestsPending] is true when libbox shows outbound connections without byte growth,
     * or a deadline crumb was seen — that must not stay pure green.
     */
    fun afterQuietWindow(
        hopUp: Boolean,
        grew: Boolean,
        sawDeadline: Boolean,
        requestsPending: Boolean = false,
    ): ChannelHealth? = when {
        grew && hopUp -> ChannelHealth.HOP_UP
        grew -> ChannelHealth.UNKNOWN
        !hopUp -> ChannelHealth.HOP_DOWN
        sawDeadline || requestsPending -> ChannelHealth.PIPE_SILENT
        else -> null
    }

    /**
     * [unfinished] flags are recent tunnel-bound rows only.
     * Empty or fully answered → null. All silent → [ChannelHealth.TUNNEL_DEAD]. Mixed → [ChannelHealth.PIPE_SILENT].
     */
    fun classifySilence(unfinished: List<Boolean>): ChannelHealth? {
        if (unfinished.isEmpty()) return null
        val silent = unfinished.count { it }
        return when {
            silent == 0 -> null
            silent == unfinished.size -> ChannelHealth.TUNNEL_DEAD
            else -> ChannelHealth.PIPE_SILENT
        }
    }

    /**
     * All recent tunnel rows silent → [SilenceReport.ALL] (the channel looks dead).
     * A proper subset → [SilenceReport.PARTIAL] (show the counts). Bare [ChannelHealth.PIPE_SILENT]
     * without a full set of rows stays partial, not “all dead”.
     */
    fun silenceReport(channel: ChannelHealth, silent: Int, tunnel: Int): SilenceReport = when {
        channel == ChannelHealth.TUNNEL_DEAD -> SilenceReport.ALL
        tunnel > 0 && silent == tunnel -> SilenceReport.ALL
        tunnel > 0 && silent in 1 until tunnel -> SilenceReport.PARTIAL
        channel == ChannelHealth.PIPE_SILENT -> SilenceReport.PARTIAL
        else -> SilenceReport.NONE
    }

    fun isHonestlyUnhealthy(channel: ChannelHealth): Boolean =
        when (channel) {
            ChannelHealth.HOP_DOWN,
            ChannelHealth.HOP_LOST,
            ChannelHealth.DATA_STALLED,
            ChannelHealth.PIPE_SILENT,
            ChannelHealth.TUNNEL_DEAD,
            -> true
            ChannelHealth.UNKNOWN,
            ChannelHealth.HOP_UP,
            -> false
        }
}
