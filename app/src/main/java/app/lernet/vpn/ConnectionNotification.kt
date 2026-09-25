package app.lernet.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import app.lernet.MainActivity
import app.lernet.R
import app.lernet.config.model.Profile
import app.lernet.config.repo.ConfigRepository
import app.lernet.engine.ConnectionController
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState
import app.lernet.engine.RunMode
import app.lernet.engine.TrafficDisplay
import app.lernet.engine.live.ChannelHealth
import app.lernet.engine.redact.LerNetLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Keeps the existing foreground notification in sync with the connection state. */
internal class ConnectionNotification(
    private val service: Service,
    private val channelId: String,
    private val notificationId: Int,
    private val mode: RunMode,
    private val controller: ConnectionController,
    private val repository: ConfigRepository,
) {
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var latest: NotificationContent? = null
    private var updateFailureLogged = false

    fun initial(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, service.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
            )
        }
        return build(latest ?: content(controller.snapshot.value, null), starting = true)
    }

    @OptIn(FlowPreview::class)
    fun observe() {
        if (job != null) return
        job = scope.launch {
            combine(controller.snapshot, repository.profiles) { snapshot, profiles ->
                content(snapshot, profiles.firstOrNull { it.id == snapshot.activeProfileId })
            }.distinctUntilChanged()
                .sample(1_000L)
                .collect { next ->
                    latest = next
                    runCatching { manager.notify(notificationId, build(next)) }
                        .onSuccess { updateFailureLogged = false }
                        .onFailure {
                            if (!updateFailureLogged) LerNetLog.w(TAG, "notification update failed: ${it.message}", it)
                            updateFailureLogged = true
                        }
                }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        scope.cancel()
    }

    private fun content(snapshot: ConnectionSnapshot, profile: Profile?): NotificationContent {
        val state = notificationState(snapshot, mode)
        val traffic = TrafficDisplay.format(
            snapshot.uplinkBps,
            snapshot.downlinkBps,
            snapshot.uplinkTotal,
            snapshot.downlinkTotal,
            snapshot.dnsOk,
        )
        return NotificationContent(
            status = state,
            profileName = profile?.name ?: service.getString(R.string.no_profile),
            tcpMs = if (state.state == ConnectionState.CONNECTED) snapshot.serverTcpMs else null,
            upRate = traffic.uplinkRate,
            downRate = traffic.downlinkRate,
            upTotal = traffic.uplinkTotal,
            downTotal = traffic.downlinkTotal,
        )
    }

    private fun build(content: NotificationContent, starting: Boolean = false): Notification {
        val status = statusText(content.status, starting)
        val title = service.getString(R.string.notification_title, service.getString(R.string.app_name), status)
        val ping = content.tcpMs?.let { service.getString(R.string.home_tcp_ping, it) }
            ?: service.getString(R.string.notification_tcp_unknown)
        val rates = service.getString(R.string.traffic_rates, content.upRate, content.downRate)
        val totals = service.getString(R.string.traffic_totals, content.upTotal, content.downTotal)
        val shortPing = content.tcpMs?.let { service.getString(R.string.notification_tcp_short, it) }
            ?: service.getString(R.string.notification_tcp_short_unknown)
        val summary = service.getString(R.string.notification_summary, content.profileName, shortPing,
            content.upRate, content.downRate)
        val details = listOf(
            service.getString(R.string.notification_profile, content.profileName),
            ping,
            rates,
            totals,
        ).joinToString("\n")
        val openApp = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(service, channelId)
            .setSmallIcon(if (content.status.needsAttention) R.drawable.ic_status_l_alert else R.drawable.ic_status_l)
            .setColor(if (content.status.needsAttention) ALERT_COLOR else NORMAL_COLOR)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .build()
    }

    private fun statusText(status: ConnectionNotificationState, starting: Boolean): String = when (status.state) {
        ConnectionState.DISCONNECTED -> service.getString(
            if (!starting) R.string.state_disconnected
            else if (mode == RunMode.FULL_VPN) R.string.vpn_starting
            else R.string.notification_proxy_starting,
        )
        ConnectionState.CONNECTING -> service.getString(R.string.notification_connecting)
        ConnectionState.RECONNECTING -> service.getString(R.string.notification_reconnecting)
        ConnectionState.FAILED -> service.getString(R.string.state_failed)
        ConnectionState.CONNECTED -> service.getString(
            when (status.channel) {
                ChannelHealth.HOP_DOWN -> R.string.channel_down
                ChannelHealth.HOP_LOST -> R.string.channel_lost
                ChannelHealth.DATA_STALLED, ChannelHealth.PIPE_SILENT -> R.string.channel_stalled
                ChannelHealth.TUNNEL_DEAD -> R.string.channel_tunnel_dead
                ChannelHealth.UNKNOWN, ChannelHealth.HOP_UP -> R.string.state_connected
            },
        )
    }

    private data class NotificationContent(
        val status: ConnectionNotificationState,
        val profileName: String,
        val tcpMs: Long?,
        val upRate: String,
        val downRate: String,
        val upTotal: String,
        val downTotal: String,
    )

    companion object {
        private const val TAG = "LerNet.Notification"
        private val ALERT_COLOR = Color.rgb(211, 47, 47)
        private val NORMAL_COLOR = Color.rgb(35, 153, 137)
    }
}
