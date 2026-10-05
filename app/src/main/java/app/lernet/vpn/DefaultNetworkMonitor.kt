package app.lernet.vpn

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import app.lernet.engine.net.UnderlyingNetworkPolicy
import app.lernet.engine.redact.LerNetLog
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Publishes the **underlay** (wifi/cell) to libbox, never the VPN TUN.
 *
 * SFA: registerDefaultNetworkCallback returns the VPN since Android P DP1.
 * We follow their requestNetwork / registerBestMatchingNetworkCallback split
 * and additionally reject TRANSPORT_VPN and the registered TUN name.
 */
object DefaultNetworkMonitor {
    private const val TAG = "LerNet.NetMon"
    private val listener = AtomicReference<InterfaceUpdateListener?>(null)
    private val underlay = AtomicReference<Network?>(null)
    private val tunName = AtomicReference<String?>(null)
    private val fingerprint = AtomicReference<String?>(null)
    private val mutableChanges = MutableStateFlow(0L)

    /** Emits only actual underlay changes; repeated capabilities callbacks do not invalidate probes. */
    val changes: StateFlow<Long> = mutableChanges.asStateFlow()

    fun currentTunName(): String? = tunName.get()

    @Volatile
    private var connectivity: ConnectivityManager? = null

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(network)

        override fun onLost(network: Network) {
            if (underlay.get() == network) {
                underlay.set(null)
                fingerprint.set(null)
                mutableChanges.update { it + 1 }
                listener.get()?.updateDefaultInterface("", -1, false, false)
                LerNetLog.w(TAG, "underlay lost")
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            publish(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            publish(network)
        }
    }

    private val request: NetworkRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

    private val callbackHandler by lazy {
        Handler(HandlerThread("lernet-net").apply { start() }.looper)
    }

    fun attach(manager: ConnectivityManager) {
        connectivity = manager
    }

    fun registerTunName(name: String?) {
        tunName.set(name?.takeIf { it.isNotBlank() })
        LerNetLog.i(TAG, "registered TUN name=${tunName.get()}")
    }

    fun underlyingNetwork(): Network? = underlay.get()

    @Synchronized
    fun setListener(next: InterfaceUpdateListener?) {
        listener.set(next)
        val manager = connectivity ?: return
        if (next != null && !registered) {
            register(manager)
            registered = true
            manager.activeNetwork?.let(::publish)
        }
        if (next == null && registered) {
            runCatching { manager.unregisterNetworkCallback(callback) }
            registered = false
        }
    }

    private fun register(manager: ConnectivityManager) {
        val result = runCatching {
            when {
                Build.VERSION.SDK_INT >= 31 ->
                    manager.registerBestMatchingNetworkCallback(request, callback, callbackHandler)
                Build.VERSION.SDK_INT >= 28 ->
                    manager.requestNetwork(request, callback, callbackHandler)
                Build.VERSION.SDK_INT >= 26 ->
                    manager.registerDefaultNetworkCallback(callback, callbackHandler)
                else ->
                    manager.registerDefaultNetworkCallback(callback)
            }
        }
        result.onFailure { error ->
            LerNetLog.e(TAG, "underlay callback register failed: ${error.message}", error)
        }
        result.onSuccess {
            LerNetLog.i(TAG, "underlay callback registered sdk=${Build.VERSION.SDK_INT}")
        }
    }

    private fun publish(network: Network) {
        val manager = connectivity ?: return
        val current = listener.get() ?: return
        val caps = manager.getNetworkCapabilities(network)
        val link = manager.getLinkProperties(network)
        val name = link?.interfaceName
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (!UnderlyingNetworkPolicy.isUsableDefault(name, tunName.get(), vpn)) {
            LerNetLog.w(TAG, "skip default iface name=$name vpn=$vpn tun=${tunName.get()}")
            return
        }
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        underlay.set(network)
        val nextFingerprint = buildString {
            append(network).append('|').append(name)
            append('|').append(link?.linkAddresses.orEmpty().map { it.toString() }.sorted())
            append('|').append(link?.dnsServers.orEmpty().mapNotNull { it.hostAddress }.sorted())
            append('|').append(link?.routes.orEmpty().map { "${it.destination}:${it.gateway?.hostAddress}" }.sorted())
            append('|').append(link?.mtu)
        }
        if (fingerprint.getAndSet(nextFingerprint) != nextFingerprint) mutableChanges.update { it + 1 }
        LerNetLog.i(TAG, "default underlay name=$name index=$index net=$network")
        current.updateDefaultInterface(name, index, expensive, false)
    }
}
