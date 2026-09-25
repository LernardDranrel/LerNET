package app.lernet.vpn

import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.live.ChannelWatch

internal data class ConnectionNotificationState(
    val state: ConnectionState,
    val channel: ChannelHealth,
    val needsAttention: Boolean,
)

internal fun notificationState(snapshot: ConnectionSnapshot, mode: RunMode): ConnectionNotificationState {
    val state = if (snapshot.mode == mode) snapshot.state else ConnectionState.DISCONNECTED
    val channel = if (state == ConnectionState.CONNECTED) snapshot.channel else ChannelHealth.UNKNOWN
    return ConnectionNotificationState(
        state = state,
        channel = channel,
        needsAttention = state == ConnectionState.RECONNECTING ||
            state == ConnectionState.FAILED ||
            (state == ConnectionState.CONNECTED && ChannelWatch.isHonestlyUnhealthy(channel)),
    )
}
