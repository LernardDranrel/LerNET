package app.lernet

import android.app.Application
import android.os.Build
import android.os.Debug
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.redact.LerNetLog
import app.lernet.log.AppLogStore
import app.lernet.log.CrashGuard
import app.lernet.vpn.LibboxNative
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject
import app.lernet.ui.expert.ExpertCoordinator

@HiltAndroidApp
class LerNetApp : Application() {
    @Inject lateinit var expertCoordinator: ExpertCoordinator
    @Inject lateinit var simpleSessionCoordinator: app.lernet.vpn.SimpleSessionCoordinator
    @Inject lateinit var subscriptions: app.lernet.subscription.SubscriptionAutoRefresh
    private val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    lateinit var logStore: AppLogStore
        private set

    override fun onCreate() {
        super.onCreate()
        app.lernet.engine.text.UserText.logger = { context, error -> LerNetLog.w("LerNet.UserText", "$context: ${error.message}", error) }
        logStore = AppLogStore(
            dir = File(filesDir, "logs"),
            filesDir = filesDir,
            publicDir = getExternalFilesDir(null),
            appContext = applicationContext,
        )
        logStore.install()
        app.lernet.vpn.DefaultNetworkMonitor.attach(getSystemService(android.net.ConnectivityManager::class.java))
        CrashGuard.install(logStore)
        LerNetLog.i(TAG, "app start sdk=${Build.VERSION.SDK_INT} debugger=${Debug.isDebuggerConnected()}")
        CrashTrail.mark(
            "application onCreate sdk=${Build.VERSION.SDK_INT} " +
                "debugger=${Debug.isDebuggerConnected()} waitForDebugger=no " +
                "firstLibboxTouch=afterThis",
        )
        LibboxNative.warmupFromApplication(this)
        CrashTrail.mark("application onCreate warmup scheduled")
        // Subscriptions refresh on their own schedule while LerNET runs (with the VPN on, the service keeps it running).
        subscriptions.start(appScope)
    }

    companion object {
        private const val TAG = "LerNet.App"
    }
}
