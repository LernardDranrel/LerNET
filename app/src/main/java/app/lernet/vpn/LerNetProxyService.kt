package app.lernet.vpn

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.os.IBinder
import app.lernet.config.repo.ConfigRepository
import app.lernet.engine.ConnectionController
import app.lernet.engine.RunMode
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.redact.LerNetLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LerNetProxyService : Service() {
    @Inject
    lateinit var controller: ConnectionController

    @Inject
    lateinit var repository: ConfigRepository

    private var platform: LibboxPlatform? = null
    private var tornDown = false
    private var statusNotification: ConnectionNotification? = null

    override fun onCreate() {
        super.onCreate()
        CrashTrail.mark("ProxyService.onCreate")
        DefaultNetworkMonitor.attach(getSystemService(ConnectivityManager::class.java))
        val next = LibboxPlatform(applicationContext, vpn = null, tunRequired = false)
        platform = next
        LibboxPlatformRegistry.register(next)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashTrail.mark("ProxyService.onStartCommand action=${intent?.action}")
        LerNetLog.i(TAG, "onStartCommand action=${intent?.action}")
        val status = statusNotification ?: ConnectionNotification(
            this, CHANNEL_ID, NOTIFICATION_ID, RunMode.PROXY, controller, repository,
        ).also { statusNotification = it }
        startLernetForeground(NOTIFICATION_ID, status.initial())
        if (intent?.action == AndroidEngineProcessHost.ACTION_HARD_STOP) {
            teardown("hard-stop")
            return START_NOT_STICKY
        }
        tornDown = false
        status.observe()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown("destroy")
        super.onDestroy()
    }

    private fun teardown(reason: String) {
        if (tornDown) return
        tornDown = true
        LerNetLog.i(TAG, "teardown $reason")
        platform?.let(LibboxPlatformRegistry::unregister)
        platform = null
        statusNotification?.close()
        statusNotification = null
        clearLernetForeground()
        runCatching { stopSelf() }
            .onFailure { LerNetLog.w(TAG, "stopSelf failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "LerNet.ProxyService"
        private const val CHANNEL_ID = "lernet.proxy"
        private const val NOTIFICATION_ID = 18
    }
}
