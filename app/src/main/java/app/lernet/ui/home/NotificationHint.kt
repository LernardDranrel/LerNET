package app.lernet.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lernet.engine.ConnectionSnapshot
import app.lernet.engine.ConnectionState

/**
 * Android 13+ asks for the notification permission. LerNET offers it once, at the first
 * connection; if the user says no, the home screen shows a soft hint that can be hidden.
 */
internal object NotificationHint {
    fun shouldAsk(context: Context, alreadyAsked: Boolean, sdk: Int = Build.VERSION.SDK_INT): Boolean =
        sdk >= Build.VERSION_CODES.TIRAMISU && !alreadyAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun visible(snapshot: ConnectionSnapshot, dismissed: Boolean, enabled: Boolean?): Boolean =
        !dismissed && enabled == false && snapshot.state == ConnectionState.CONNECTED
}
