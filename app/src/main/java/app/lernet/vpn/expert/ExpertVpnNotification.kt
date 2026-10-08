package app.lernet.vpn.expert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.lernet.MainActivity
import app.lernet.R
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
        return NotificationCompat.Builder(service, channelId)
            .setSmallIcon(if (status == ExpertNotificationStatus.FAILED) R.drawable.ic_status_l_alert else R.drawable.ic_status_l)
            .setContentTitle(service.getString(R.string.expert_platform_notification_title))
            .setContentText(service.getString(message))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(false)
            .build()
    }
}
