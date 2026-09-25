package app.lernet.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.lernet.engine.EngineHostPlanner
import app.lernet.engine.HostAction
import app.lernet.engine.HostCommand
import app.lernet.engine.HostTarget
import app.lernet.engine.RunMode
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.redact.LerNetLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

interface EngineProcessHost {
    fun start(mode: RunMode)

    fun stop()
}

@Singleton
class AndroidEngineProcessHost @Inject constructor(
    @ApplicationContext private val context: Context,
) : EngineProcessHost {
    private val vpnRunning = AtomicBoolean(false)
    private val proxyRunning = AtomicBoolean(false)

    override fun start(mode: RunMode) {
        val commands = EngineHostPlanner.start(mode, vpnRunning.get(), proxyRunning.get())
        LerNetLog.i(TAG, "host.start $mode commands=$commands")
        commands.forEach(::execute)
        when (mode) {
            RunMode.FULL_VPN -> {
                vpnRunning.set(true)
                proxyRunning.set(false)
            }
            RunMode.PROXY -> {
                proxyRunning.set(true)
                vpnRunning.set(false)
            }
        }
    }

    override fun stop() {
        val commands = EngineHostPlanner.stopAll(vpnRunning.get(), proxyRunning.get())
        LerNetLog.i(TAG, "host.stop commands=$commands")
        commands.forEach(::execute)
        vpnRunning.set(false)
        proxyRunning.set(false)
    }

    private fun execute(command: HostCommand) {
        val service = serviceOf(command.target)
        when (command.action) {
            HostAction.START_FOREGROUND -> {
                CrashTrail.mark("host START_FOREGROUND ${command.target}")
                runCatching { ContextCompat.startForegroundService(context, Intent(context, service)) }
                    .onFailure { LerNetLog.e(TAG, "startForegroundService ${command.target} failed: ${it.message}", it) }
            }
            HostAction.SIGNAL_STOP ->
                runCatching { context.startService(Intent(context, service).setAction(ACTION_HARD_STOP)) }
                    .onFailure { LerNetLog.w(TAG, "signal stop ${command.target} failed: ${it.message}", it) }
            HostAction.STOP_SERVICE ->
                runCatching { context.stopService(Intent(context, service)) }
                    .onFailure { LerNetLog.w(TAG, "stopService ${command.target} failed: ${it.message}", it) }
        }
    }

    private fun serviceOf(target: HostTarget): Class<*> =
        when (target) {
            HostTarget.VPN -> LerNetVpnService::class.java
            HostTarget.PROXY -> LerNetProxyService::class.java
        }

    companion object {
        const val ACTION_HARD_STOP = "app.lernet.action.HARD_STOP"
        private const val TAG = "LerNet.Host"
    }
}
