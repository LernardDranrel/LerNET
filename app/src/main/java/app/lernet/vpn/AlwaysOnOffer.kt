package app.lernet.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.lernet.MainActivity
import app.lernet.R
import app.lernet.engine.redact.LerNetLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android started LerNET as the always-on VPN, but there is no saved connection to restore
 * (for example after an update or after the user pressed Stop). Instead of silently doing
 * nothing, offer to connect. A tap opens LerNET and connects to the last used profile.
 */
object AlwaysOnOffer {
    const val ACTION_CONNECT_LAST = "app.lernet.action.CONNECT_LAST"
    private const val CHANNEL_ID = "lernet_connect_offer"
    private const val NOTIFICATION_ID = 7_301

    private val pending = MutableStateFlow(false)

    /** True while a tap on the offer waits for the home screen to connect. */
    val connectRequested: StateFlow<Boolean> = pending.asStateFlow()

    fun show(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            LerNetLog.w(TAG, "always-on offer not shown: notifications are off")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.always_on_offer_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_CONNECT_LAST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status_l_alert)
            .setContentTitle(context.getString(R.string.always_on_offer_title))
            .setContentText(context.getString(R.string.always_on_offer_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.always_on_offer_body)))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { LerNetLog.w(TAG, "always-on offer failed: ${it.javaClass.simpleName}") }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** Called by the activity for a tap on the offer. */
    fun onTapped(context: Context) {
        cancel(context)
        pending.value = true
    }

    /** The home screen takes the request once; returns false if there was none. */
    fun consume(): Boolean = pending.compareAndSet(true, false)

    private const val TAG = "LerNET.AlwaysOn"
}
