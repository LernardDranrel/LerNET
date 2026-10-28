package app.lernet.vpn.expert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import app.lernet.MainActivity
import app.lernet.R
import app.lernet.engine.text.StatusWords
import app.lernet.vpn.LerNetVpnService

internal object ExpertVpnNotification {
    fun build(service: LerNetVpnService, channelId: String, status: ExpertNotificationStatus = ExpertNotificationStatus.STARTING): Notification {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, service.getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW),
        )
        val openApp = PendingIntent.getActivity(
            service,
            11,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val message = when (status) {
            ExpertNotificationStatus.ACTIVE -> R.string.expert_platform_notification_text
            ExpertNotificationStatus.OFFLINE -> R.string.expert_notification_waiting
            ExpertNotificationStatus.RECOVERING -> R.string.expert_notification_recovering
            ExpertNotificationStatus.STOPPING -> R.string.expert_notification_stopping
            ExpertNotificationStatus.FAILED -> R.string.expert_notification_failed
            ExpertNotificationStatus.STOPPED -> R.string.state_disconnected
            ExpertNotificationStatus.STARTING -> R.string.expert_platform_notification_starting
        }
        val attention = expertNeedsAttention(status)
        return NotificationCompat.Builder(service, channelId)
            .setSmallIcon(if (attention) R.drawable.ic_status_l_alert else R.drawable.ic_status_l)
            .setColor(if (attention) ALERT_COLOR else NORMAL_COLOR)
            .setContentTitle(StatusWords.chrome(StatusWords.EXPERT))
            .setContentText(service.getString(message))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .build()
    }

    private val ALERT_COLOR = Color.rgb(211, 47, 47)
    private val NORMAL_COLOR = Color.rgb(35, 153, 137)
}
