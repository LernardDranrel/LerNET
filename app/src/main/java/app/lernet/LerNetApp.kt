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
    lateinit var logStore: AppLogStore
        private set

    override fun onCreate() {
        super.onCreate()
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
    }

    companion object {
        private const val TAG = "LerNet.App"
    }
}
