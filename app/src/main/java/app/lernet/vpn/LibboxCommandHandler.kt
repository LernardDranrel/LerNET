package app.lernet.vpn

import app.lernet.engine.redact.LerNetLog
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.SystemProxyStatus

internal class LibboxCommandHandler(
    private val onServiceStop: () -> Unit,
) : CommandServerHandler {
    override fun connectSSHAgent(): Int = error("ssh agent is not available")

    override fun getSystemProxyStatus(): SystemProxyStatus {
        val status = SystemProxyStatus()
        status.available = false
        status.enabled = false
        return status
    }

    override fun serviceReload() = Unit

    override fun serviceStop() {
        onServiceStop()
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun triggerNativeCrash() = Unit

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) {
            LerNetLog.i(TAG, message)
        }
    }

    companion object {
        private const val TAG = "LerNet.Libbox"
    }
}
