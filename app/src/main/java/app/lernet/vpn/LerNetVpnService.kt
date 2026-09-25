package app.lernet.vpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import app.lernet.config.repo.ConfigRepository
import app.lernet.engine.ConnectionController
import app.lernet.engine.LibboxBoxEngine
import app.lernet.engine.RunMode
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.redact.LerNetLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LerNetVpnService : VpnService(), TunOwner {
    @Inject
    lateinit var engine: LibboxBoxEngine

    @Inject
    lateinit var controller: ConnectionController

    @Inject
    lateinit var repository: ConfigRepository

    private var platform: LibboxPlatform? = null
    private var tunPfd: ParcelFileDescriptor? = null
    private var tornDown = false
    private var statusNotification: ConnectionNotification? = null

    override fun onCreate() {
        super.onCreate()
        CrashTrail.mark("VpnService.onCreate")
        DefaultNetworkMonitor.attach(getSystemService(ConnectivityManager::class.java))
        VpnRuntime.attach(this)
        VpnRuntime.setRevokeSink { engine.notifyRevoked() }
        val next = LibboxPlatform(applicationContext, vpn = this, tunRequired = true)
        platform = next
        LibboxPlatformRegistry.register(next)
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashTrail.mark("VpnService.onStartCommand action=${intent?.action}")
        LerNetLog.i(TAG, "onStartCommand action=${intent?.action}")
        val status = statusNotification ?: ConnectionNotification(
            this, CHANNEL_ID, NOTIFICATION_ID, RunMode.FULL_VPN, controller, repository,
        ).also { statusNotification = it }
        startLernetForeground(NOTIFICATION_ID, status.initial())
        if (intent?.action == AndroidEngineProcessHost.ACTION_HARD_STOP) {
            teardown("hard-stop")
            return START_NOT_STICKY
        }
        tornDown = false
        status.observe()
        VpnRuntime.attach(this)
        VpnRuntime.setRevokeSink { engine.notifyRevoked() }
        if (platform == null) {
            val next = LibboxPlatform(applicationContext, vpn = this, tunRequired = true)
            platform = next
            LibboxPlatformRegistry.register(next)
        }
        return START_NOT_STICKY
    }

    override fun retainTun(pfd: ParcelFileDescriptor) {
        tunPfd?.close()
        tunPfd = pfd
    }

    override fun onRevoke() {
        LerNetLog.w(TAG, "VPN revoked by system")
        engine.notifyRevoked()
        teardown("revoked")
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
        VpnRuntime.setRevokeSink(null)
        VpnRuntime.detach(this)
        DefaultNetworkMonitor.registerTunName(null)
        runCatching { tunPfd?.close() }
            .onFailure { LerNetLog.w(TAG, "tun close failed: ${it.message}", it) }
        tunPfd = null
        statusNotification?.close()
        statusNotification = null
        clearLernetForeground()
        runCatching { stopSelf() }
            .onFailure { LerNetLog.w(TAG, "stopSelf failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "LerNet.VpnService"
        private const val CHANNEL_ID = "lernet.vpn"
        private const val NOTIFICATION_ID = 17
    }
}
