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
import app.lernet.vpn.expert.ExpertServiceRestorer
import app.lernet.vpn.expert.ExpertVpnNotification
import app.lernet.vpn.expert.ExpertVpnSession
import app.lernet.vpn.expert.ExpertVpnToken
import app.lernet.vpn.expert.RetainedTunDescriptor
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

@AndroidEntryPoint
class LerNetVpnService : VpnService(), TunOwner {
    @Inject
    lateinit var engine: LibboxBoxEngine

    @Inject
    lateinit var controller: ConnectionController

    @Inject
    lateinit var repository: ConfigRepository

    private var platform: LibboxPlatform? = null
    private val tunDescriptor = RetainedTunDescriptor<ParcelFileDescriptor> { it.close() }

    @Volatile
    private var tornDown = false
    private var reservationFailed = false
    private var teardownConfirmed = false
    private var hasStarted = false
    private var statusNotification: ConnectionNotification? = null
    private val notificationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private var expertNotificationJob: kotlinx.coroutines.Job? = null
    private val serviceInstance = UUID.randomUUID().toString()

    @Synchronized
    override fun onCreate() {
        super.onCreate()
        CrashTrail.mark("VpnService.onCreate")
        DefaultNetworkMonitor.attach(getSystemService(ConnectivityManager::class.java))
        try {
            ExpertVpnSession.attachService(serviceInstance)
        } catch (failure: IllegalStateException) {
            reservationFailed = true
            LerNetLog.w(TAG, "Expert VPN service instance was replaced", failure)
            teardownSafely("service-recreated")
            return
        }
        VpnRuntime.attach(this)
        VpnRuntime.setRevokeSink(::notifyRevoked)
        // Native may borrow the platform only after an actual start intent is accepted.
        // A cancelled foreground-start bootstrap is still visible for scoped cleanup.
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    @Synchronized
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashTrail.mark("VpnService.onStartCommand action=${intent?.action}")
        LerNetLog.i(TAG, "onStartCommand action=${intent?.action}")
        // Simple's asynchronous stop intent can outlive its handover to Expert.
        if (intent?.action == AndroidEngineProcessHost.ACTION_HARD_STOP && ExpertVpnSession.isOwned) {
            LerNetLog.i(TAG, "Ignoring Simple stop after Expert reservation")
            return START_NOT_STICKY
        }
        if (intent?.action == ExpertVpnSession.ACTION_START &&
            !ExpertVpnSession.acceptsServiceStart(
                ExpertVpnToken(intent.getLongExtra(ExpertVpnSession.EXTRA_GENERATION, -1L)), serviceInstance,
            )
        ) {
            // A cancelled foreground-start request may arrive after its reservation was
            // released. Acknowledge its foreground request, then stop only that startId
            // when there is no current owner or descriptor. Newer Simple starts survive.
            if (!hasStarted || tornDown) {
                startLernetForeground(NOTIFICATION_ID, ExpertVpnNotification.build(this, CHANNEL_ID))
                if (!ExpertVpnSession.isOwned && tunDescriptor.descriptor == null) {
                    runCatching {
                        if (stopSelfResult(startId)) teardown("stale-expert-start", stopStartId = startId)
                    }
                        .onFailure { LerNetLog.w(TAG, "stale Expert stopSelf failed", it) }
                }
            }
            return START_NOT_STICKY
        }
        if (tornDown && !teardownConfirmed) {
            teardownSafely("close-unconfirmed")
            return START_NOT_STICKY
        }
        try {
            ExpertVpnSession.attachService(serviceInstance)
        } catch (failure: IllegalStateException) {
            startLernetForeground(NOTIFICATION_ID, ExpertVpnNotification.build(this, CHANNEL_ID))
            LerNetLog.w(TAG, "Expert VPN service cannot resume its closed reservation", failure)
            teardownSafely("service-replaced")
            return START_NOT_STICKY
        }
        val expert = ExpertVpnSession.isOwned
        val status = if (expert) {
            null
        } else {
            statusNotification ?: ConnectionNotification(
                this, CHANNEL_ID, NOTIFICATION_ID, RunMode.FULL_VPN, controller, repository,
            ).also { statusNotification = it }
        }
        startLernetForeground(NOTIFICATION_ID, status?.initial() ?: ExpertVpnNotification.build(this, CHANNEL_ID))
        if (reservationFailed) {
            clearLernetForeground()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == AndroidEngineProcessHost.ACTION_HARD_STOP) {
            teardownSafely("hard-stop")
            return START_NOT_STICKY
        }
        val systemStartup = intent?.action == null || intent.action == VpnService.SERVICE_INTERFACE
        if (systemStartup && !expert && ExpertServiceRestorer.shouldRestore()) {
            // The system created a bootstrap service, not a native TUN. Restore through the same
            // serialized engine entry point after releasing this empty service's platform handle.
            teardownSafely("restore-bootstrap")
            ExpertServiceRestorer.restore()
            return START_NOT_STICKY
        }
        if (systemStartup && !expert) SimpleServiceRestorer.restore()
        tornDown = false
        teardownConfirmed = false
        hasStarted = true
        status?.observe()
        if (expert && expertNotificationJob == null) {
            expertNotificationJob = notificationScope.launch {
                app.lernet.vpn.expert.ExpertNotificationFeed.status.collect { current ->
                    runCatching {
                        getSystemService(android.app.NotificationManager::class.java).notify(
                            NOTIFICATION_ID, ExpertVpnNotification.build(this@LerNetVpnService, CHANNEL_ID, current),
                        )
                    }.onFailure { LerNetLog.w(TAG, "Expert notification update failed", it) }
                }
            }
        }
        VpnRuntime.attach(this)
        VpnRuntime.setRevokeSink(::notifyRevoked)
        if (platform == null) {
            val next = LibboxPlatform(applicationContext, vpn = this, tunRequired = true, captureAllApplications = expert)
            platform = next
            LibboxPlatformRegistry.register(next)
        }
        return START_NOT_STICKY
    }

    @Synchronized
    override fun retainTun(pfd: ParcelFileDescriptor) {
        if (tornDown) {
            pfd.close()
            error("VPN service stopped while its TUN was being established")
        }
        try {
            ExpertVpnSession.tunEstablished(serviceInstance, pfd.fd)
        } catch (failure: IllegalStateException) {
            pfd.close()
            throw failure
        }
        try {
            tunDescriptor.close()
            tunDescriptor.retain(pfd)
        } catch (failure: Throwable) {
            runCatching { pfd.close() }.onFailure(failure::addSuppressed)
            throw failure
        }
    }

    @Synchronized
    override fun beforeOpenTun() {
        check(!tornDown) { "VPN service is no longer running" }
        ExpertVpnSession.requireFirstTun(serviceInstance)
    }

    @Synchronized
    override fun onRevoke() {
        if (teardownConfirmed ||
            VpnRuntime.current() !== this ||
            (ExpertVpnSession.isOwned && !ExpertVpnSession.ownsService(serviceInstance))
        ) {
            teardownSafely("revoked-stale")
            return
        }
        LerNetLog.w(TAG, "VPN revoked by system")
        ExpertServiceRestorer.revoked()
        runCatching { SimpleServiceRestorer.clear() }
            .onFailure { LerNetLog.e(TAG, "Simple revoke intent write failed (${it.javaClass.simpleName})") }
        notifyRevoked()
        teardownSafely("revoked")
    }

    @Synchronized
    override fun onDestroy() {
        teardownSafely("destroy")
        notificationScope.cancel()
        super.onDestroy()
    }

    @Synchronized
    fun ownsExpert(expected: ExpertVpnToken): Boolean = ExpertVpnSession.ownsService(expected, serviceInstance)

    /** Native closes its duplicate first; this confirms only the service's original PFD. */
    @Synchronized
    fun stopExpert(expected: ExpertVpnToken) {
        ExpertVpnSession.closeService(expected, serviceInstance) { teardown("expert-stop", intentional = true) }
    }

    private fun teardownSafely(reason: String) {
        runCatching { teardown(reason) }
            .onFailure { LerNetLog.w(TAG, "VPN teardown remains unconfirmed: $reason", it) }
    }

    @Synchronized
    private fun teardown(reason: String, intentional: Boolean = false, stopStartId: Int? = null) {
        if (teardownConfirmed) {
            if (stopStartId != null) {
                clearLernetForeground()
                requestSelfStop(stopStartId)
            }
            return
        }
        if (!tornDown && !intentional) ExpertVpnSession.signalFailure(serviceInstance, reason)
        tornDown = true
        LerNetLog.i(TAG, "teardown $reason")
        // Close before detaching any ownership evidence. The descriptor and its first
        // close error stay retained if Android cannot confirm the physical close.
        tunDescriptor.close()
        ExpertVpnSession.serviceDetached(serviceInstance)
        platform?.let(LibboxPlatformRegistry::unregister)
        platform = null
        if (VpnRuntime.current() === this) {
            VpnRuntime.setRevokeSink(null)
            DefaultNetworkMonitor.registerTunName(null)
            VpnRuntime.detach(this)
        }
        teardownConfirmed = true
        expertNotificationJob?.cancel()
        expertNotificationJob = null
        statusNotification?.close()
        statusNotification = null
        clearLernetForeground()
        requestSelfStop(stopStartId)
    }

    private fun requestSelfStop(startId: Int?) {
        runCatching { if (startId == null) stopSelf() else stopSelf(startId) }
            .onFailure { LerNetLog.w(TAG, "stopSelf failed: ${it.message}", it) }
    }

    @Synchronized
    private fun notifyRevoked() {
        if (tornDown || VpnRuntime.current() !== this) return
        if (ExpertVpnSession.isOwned) ExpertVpnSession.signalFailure(serviceInstance, "revoked") else engine.notifyRevoked()
    }

    companion object {
        private const val TAG = "LerNet.VpnService"
        private const val CHANNEL_ID = "lernet.vpn"
        private const val NOTIFICATION_ID = 17
    }
}
