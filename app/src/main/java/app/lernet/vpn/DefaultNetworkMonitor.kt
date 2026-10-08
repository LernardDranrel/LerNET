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
 * We use a NOT_VPN request on every supported API, with best-matching callbacks on API 31+
 * and additionally reject TRANSPORT_VPN and the registered TUN name.
 */
object DefaultNetworkMonitor {
    private const val TAG = "LerNet.NetMon"
    private val listener = AtomicReference<InterfaceUpdateListener?>(null)
    private var listenerOwner: Any? = null
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

    // Observe the physical network for the whole process, including failed native starts.
    // Removing a libbox listener must not blind the deferred reconnection policy.
    private var processObservation = false

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val facts = UnderlayCallbackState<Network, NetworkCapabilities, LinkProperties>()
    private val retryRegistration = Runnable { synchronized(this) {
        if ((processObservation || listener.get() != null) && !registered) connectivity?.let(::register)
    } }

    private fun newCallback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = synchronized(this@DefaultNetworkMonitor) {
            if (callback !== this) return@synchronized
            facts.available(network)
            if (underlay.get() != network) clearUnderlay()
        }

        override fun onLost(network: Network) = synchronized(this@DefaultNetworkMonitor) {
            if (callback === this && facts.lost(network)) clearUnderlay()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            synchronized(this@DefaultNetworkMonitor) {
                if (callback === this) facts.capabilities(network, NetworkCapabilities(capabilities))?.let {
                    publish(it.network, it.capabilities, it.link)
                }
            }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) =
            synchronized(this@DefaultNetworkMonitor) {
                if (callback === this) facts.link(network, properties)?.let {
                    publish(it.network, it.capabilities, it.link)
                }
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

    @Synchronized fun attach(manager: ConnectivityManager) {
        connectivity = manager
        processObservation = true
        if (!registered) register(manager)
    }

    fun registerTunName(name: String?) {
        tunName.set(name?.takeIf { it.isNotBlank() })
        LerNetLog.i(TAG, "registered TUN name=${tunName.get()}")
    }

    fun underlyingNetwork(): Network? = underlay.get()

    @Synchronized
    fun setListener(next: InterfaceUpdateListener?, owner: Any?) {
        listenerOwner = owner.takeIf { next != null }
        listener.set(next)
        val manager = connectivity ?: return
        if (next != null && !registered) {
            register(manager)
        } else if (next != null) {
            facts.snapshot()?.let { publish(it.network, it.capabilities, it.link) }
        }
        if (next == null && !processObservation) {
            callbackHandler.removeCallbacks(retryRegistration)
            val previous = callback
            callback = null
            if (registered && previous != null) {
                runCatching { manager.unregisterNetworkCallback(previous) }
                    .onFailure { LerNetLog.w(TAG, "underlay unregister failed", it) }
            }
            registered = false
            facts.clear()
            clearUnderlay()
        }
    }

    @Synchronized fun removeListener(owner: Any) {
        // gomobile can supply a new Java proxy for the same Go listener on Close.
        // Platform ownership fences cleanup without relying on Java proxy identity.
        if (listenerOwner === owner) setListener(null, null)
    }

    private fun register(manager: ConnectivityManager) {
        callbackHandler.removeCallbacks(retryRegistration)
        val next = newCallback()
        callback = next
        val result = runCatching {
            when {
                Build.VERSION.SDK_INT >= 31 ->
                    manager.registerBestMatchingNetworkCallback(request, next, callbackHandler)
                else -> manager.requestNetwork(request, next, callbackHandler)
            }
        }
        result.onFailure { error ->
            callback = null
            registered = false
            facts.clear()
            clearUnderlay()
            LerNetLog.e(TAG, "underlay callback register failed: ${error.message}", error)
            if (processObservation || listener.get() != null) callbackHandler.postDelayed(retryRegistration, 5_000)
        }
        result.onSuccess {
            registered = true
            LerNetLog.i(TAG, "underlay callback registered sdk=${Build.VERSION.SDK_INT}")
            // Bootstrap outside callback delivery. All subsequent updates use callback arguments.
            runCatching { manager.activeNetwork?.let { network ->
                val caps = manager.getNetworkCapabilities(network)
                val link = manager.getLinkProperties(network)
                if (caps != null && link != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    facts.available(network)
                    facts.capabilities(network, caps)
                    facts.link(network, link)?.let { publish(it.network, it.capabilities, it.link) }
                }
            } }.onFailure { LerNetLog.w(TAG, "underlay bootstrap snapshot unavailable", it) }
        }
    }

    private fun clearUnderlay() {
        if (underlay.getAndSet(null) != null || fingerprint.getAndSet(null) != null) {
            fingerprint.set(null)
            mutableChanges.update { it + 1 }
        }
        runCatching { listener.get()?.updateDefaultInterface("", -1, false, false) }
            .onFailure { LerNetLog.w(TAG, "native underlay loss delivery failed", it) }
    }

    private fun publish(network: Network, caps: NetworkCapabilities, link: LinkProperties) {
        val current = listener.get()
        val name = link.interfaceName
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (!UnderlyingNetworkPolicy.isUsableDefault(name, tunName.get(), vpn)) {
            clearUnderlay()
            LerNetLog.w(TAG, "skip default iface name=$name vpn=$vpn tun=${tunName.get()}")
            return
        }
        val index = runCatching { NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
        val expensive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        underlay.set(network)
        val nextFingerprint = buildString {
            append(network).append('|').append(name)
            append('|').append(link.linkAddresses.orEmpty().map { it.toString() }.sorted())
            append('|').append(link.dnsServers.orEmpty().mapNotNull { it.hostAddress }.sorted())
            append('|').append(link.routes.orEmpty().map { "${it.destination}:${it.gateway?.hostAddress}" }.sorted())
            append('|').append(if (Build.VERSION.SDK_INT >= 29) link.mtu else 0)
            if (Build.VERSION.SDK_INT >= 28) {
                append('|').append(link.isPrivateDnsActive).append('|').append(link.privateDnsServerName)
            }
        }
        if (fingerprint.getAndSet(nextFingerprint) != nextFingerprint) {
            mutableChanges.update { it + 1 }
            val privateDns = if (Build.VERSION.SDK_INT >= 28 && link.isPrivateDnsActive == true) {
                link.privateDnsServerName ?: "системный автоматический режим"
            } else {
                "выключен"
            }
            val dnsServers = link.dnsServers.orEmpty().mapNotNull { it.hostAddress }.joinToString()
            LerNetLog.i(TAG, "DNS исходной сети: $dnsServers; Private DNS: $privateDns")
        }
        LerNetLog.i(TAG, "default underlay name=$name index=$index net=$network")
        runCatching { current?.updateDefaultInterface(name, index, expensive, false) }
            .onFailure { LerNetLog.w(TAG, "native underlay delivery failed", it) }
    }
}
