package app.lernet.vpn.expert

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Process
import app.lernet.vpn.DefaultNetworkMonitor
import app.lernet.vpn.VpnRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class AndroidVpnLockdown { ENABLED, DISABLED, UNKNOWN }

data class AndroidExpertEnvironmentState(
    val permissionIntent: Intent?,
    val visibleVpnInterfaces: List<String>,
    val ownsVpnSession: Boolean,
    val lockdown: AndroidVpnLockdown,
    val packageAttributionAvailable: Boolean,
) {
    val anotherVpnVisible: Boolean get() = visibleVpnInterfaces.isNotEmpty() && !ownsVpnSession
    val crashProtectionVerified: Boolean get() = lockdown == AndroidVpnLockdown.ENABLED
}

/** Reads platform evidence only. A detected VPN is not silently replaced by an Expert session. */
@Singleton
class AndroidExpertEnvironment @Inject constructor(@ApplicationContext private val context: Context) {
    fun inspect(): AndroidExpertEnvironmentState {
        val service = VpnRuntime.current()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        var ownVpnVisible = false
        val vpnNames = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val name = manager.getLinkProperties(network)?.interfaceName
            val owned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                capabilities.ownerUid == Process.myUid()
            } else {
                name != null && name == DefaultNetworkMonitor.currentTunName()
            }
            if (owned) ownVpnVisible = true
            name ?: "VPN"
        }.distinct()
        val lockdown = when {
            service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> AndroidVpnLockdown.UNKNOWN
            service.isLockdownEnabled -> AndroidVpnLockdown.ENABLED
            else -> AndroidVpnLockdown.DISABLED
        }
        return AndroidExpertEnvironmentState(
            permissionIntent = VpnService.prepare(context),
            visibleVpnInterfaces = vpnNames,
            ownsVpnSession = service != null && ownVpnVisible,
            lockdown = lockdown,
            packageAttributionAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
    }
}
