package app.lernet.engine

import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.net.HopStop

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED,
}

data class FailoverBanner(
    val fromName: String,
    val toName: String,
    val groupName: String,
)

data class ConnectionSnapshot(
    val state: ConnectionState,
    val cause: ConnectionCause?,
    val mode: RunMode,
    val activeProfileId: String?,
    val activeOutboundId: String?,
    val attempt: Int,
    val banner: FailoverBanner?,
    val compiledJson: String?,
    val uplinkBps: Long = 0,
    val downlinkBps: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
    val dnsOk: Boolean = false,
    val channel: ChannelHealth = ChannelHealth.UNKNOWN,
    val pipeSilentCount: Int = 0,
    val pipeTunnelCount: Int = 0,
    val hopHost: String = "",
    val hopTimedOut: Boolean = false,
    val hopChecked: Boolean = false,
    val hopRunning: Boolean = false,
    val hops: List<HopStop> = emptyList(),
    /** TCP setup time to the server over the underlying network; not ICMP RTT. */
    val serverTcpMs: Long? = null,
    /** No underlying network: the VPN is held and nothing is blamed on the config ([app.lernet.engine.policy.LinkJudge]). */
    val waitingForNetwork: Boolean = false,
    /** Mockup 10: configs tried while the folder had no working backup; null once a config works. */
    val tried: app.lernet.engine.policy.FailoverReport? = null,
) {
    companion object {
        fun idle(mode: RunMode = RunMode.FULL_VPN): ConnectionSnapshot =
            ConnectionSnapshot(
                state = ConnectionState.DISCONNECTED,
                cause = null,
                mode = mode,
                activeProfileId = null,
                activeOutboundId = null,
                attempt = 0,
                banner = null,
                compiledJson = null,
            )
    }
}
