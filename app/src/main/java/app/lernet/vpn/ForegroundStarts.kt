package app.lernet.vpn

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import app.lernet.engine.redact.LerNetLog

internal fun Service.startLernetForeground(id: Int, notification: Notification) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }.onFailure { error ->
        LerNetLog.e(TAG, "startForeground failed: ${error.message}", error)
        throw error
    }
}

internal fun Service.clearLernetForeground() {
    runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        .onFailure { LerNetLog.w(TAG, "stopForeground failed: ${it.message}", it) }
}

private const val TAG = "LerNet.Fgs"
