package app.lernet.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import app.lernet.engine.log.CrashTrail
import app.lernet.engine.nativebridge.PlatformJni
import app.lernet.engine.net.UnderlyingNetworkPolicy
import app.lernet.engine.net.VpnGuard
import app.lernet.engine.redact.LerNetLog
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface

class LibboxPlatform(
    private val context: Context,
    private val vpn: VpnService?,
    private val tunRequired: Boolean,
    private val captureAllApplications: Boolean = false,
) : PlatformInterface {
    @Volatile
    private var myTunName: String? = null

    private val underlayDns = UnderlayDnsTransport(context)
    override fun localDNSTransport(): LocalDNSTransport = underlayDns

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        jniCall("protect") {
            val service = vpn
            CrashTrail.mark("before protect fd=$fd tunRequired=$tunRequired hasVpn=${service != null}")
            val protectOk = service?.protect(fd)
            when (val decided = VpnGuard.decideProtect(tunRequired, service != null, protectOk, fd)) {
                VpnGuard.ProtectOutcome.SkipProxy ->
                    LerNetLog.w(TAG, "protect($fd) skipped: PROXY has no TUN")
                VpnGuard.ProtectOutcome.Ok -> {
                    CrashTrail.mark("protect($fd) ok")
                    LerNetLog.i(TAG, "protect($fd) ok")
                }
                is VpnGuard.ProtectOutcome.Fail -> {
                    CrashTrail.mark(decided.crumb)
                    LerNetLog.e(TAG, decided.message)
                    VpnRuntime.signalRevoked(decided.crumb)
                    throw Exception(decided.message)
                }
            }
        }
    }

    override fun openTun(options: TunOptions): Int =
        jniCall("openTun") {
            CrashTrail.mark("PlatformInterface.openTun")
            val service = vpn ?: throw Exception("proxy mode has no TUN")
            (service as? TunOwner)?.beforeOpenTun()
            val pfd = try {
                TunEstablisher.establish(service, options, context.packageName, captureAllApplications)
            } catch (error: Throwable) {
                CrashTrail.recordFailure("openTun.establish", error)
                throw error
            }
            if (service is TunOwner) {
                service.retainTun(pfd)
            }
            // The pinned libbox platform wrapper duplicates this borrowed descriptor before
            // handing it to NativeTun. VpnService retains and closes the original PFD.
            val nativeDescriptor = pfd.fd
            CrashTrail.mark("PlatformInterface.openTun fd=$nativeDescriptor serviceFd=${pfd.fd}")
            nativeDescriptor
        }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner =
        jniCall("findConnectionOwner") {
            connectionOwnerOf(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
        }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        jniRun("startDefaultInterfaceMonitor") { DefaultNetworkMonitor.setListener(listener, this) }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        jniRun("closeDefaultInterfaceMonitor") { DefaultNetworkMonitor.removeListener(this) }
    }

    override fun getInterfaces(): NetworkInterfaceIterator =
        jniOrElse(InterfaceArray(emptyList())) { InterfaceArray(collectInterfaces()) }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() = Unit

    override fun sendNotification(notification: Notification?) = Unit

    override fun cancelNotification(identifier: String?, typeId: Int) = Unit

    override fun usePlatformBridge(): Boolean = false

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell(): Unit = throw Exception("platform shell is disabled")

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw Exception("bridge is disabled")

    override fun lookupSFTPServer(): String = throw Exception("sftp is disabled")

    override fun lookupUser(username: String?): PlatformUser =
        throw Exception("platform users are disabled")

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        args: StringIterator?,
        workingDir: String?,
        cols: Int,
        rows: Int,
    ): ShellSession = throw Exception("platform shell is disabled")

    override fun readSystemSSHHostKey(): String = ""

    override fun registerMyInterface(name: String?) {
        jniRun("registerMyInterface") {
            myTunName = name?.takeIf { it.isNotBlank() }
            DefaultNetworkMonitor.registerTunName(myTunName)
            LerNetLog.i(TAG, "registerMyInterface name=$myTunName")
        }
    }

    override fun tailscaleHostname(): String = ""

    private fun connectionOwnerOf(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw Exception("procfs path is enabled")
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val src = InetSocketAddress(sourceAddress.orEmpty(), sourcePort)
        val dst = InetSocketAddress(destinationAddress.orEmpty(), destinationPort)
        val uid = connectivity.getConnectionOwnerUid(ipProtocol, src, dst)
        val owner = ConnectionOwner()
        owner.userId = if (uid >= 0) uid else Process.INVALID_UID
        owner.userName = ""
        owner.processPath = ""
        val packages = if (uid >= 0) {
            context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        } else {
            emptyList()
        }
        owner.setAndroidPackageNames(StringArray(packages))
        return owner
    }

    private fun collectInterfaces(): List<LibboxNetworkInterface> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val javaIfaces = JavaNetworkInterface.getNetworkInterfaces().toList()
        val result = mutableListOf<LibboxNetworkInterface>()
        connectivity.allNetworks.forEach { network ->
            val link = connectivity.getLinkProperties(network) ?: return@forEach
            val caps = connectivity.getNetworkCapabilities(network) ?: return@forEach
            val name = link.interfaceName ?: return@forEach
            val vpnTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            if (!UnderlyingNetworkPolicy.shouldExposeToLibbox(name, myTunName, vpnTransport)) {
                return@forEach
            }
            val javaIface = javaIfaces.firstOrNull { it.name == name } ?: return@forEach
            result += toLibboxInterface(name, javaIface, link, caps)
        }
        return result
    }

    private fun toLibboxInterface(
        name: String,
        javaIface: JavaNetworkInterface,
        link: LinkProperties,
        caps: NetworkCapabilities,
    ): LibboxNetworkInterface {
        val item = LibboxNetworkInterface()
        item.name = name
        item.index = javaIface.index
        item.setMTU(runCatching { javaIface.mtu }.getOrDefault(1500))
        item.dnsServer = StringArray(link.dnsServers.mapNotNull { it.hostAddress })
        item.addresses = StringArray(javaIface.interfaceAddresses.map { address -> prefixOf(address) })
        item.gateway = StringArray(gatewaysOf(link))
        item.type = interfaceType(caps)
        item.flags = dumpFlags(javaIface, caps)
        item.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return item
    }

    private fun gatewaysOf(link: LinkProperties): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            link.routes.mapNotNull { route -> route.gateway?.hostAddress }
        } else {
            emptyList()
        }

    private fun prefixOf(address: InterfaceAddress): String {
        val host = if (address.address is Inet6Address) {
            Inet6Address.getByAddress(address.address.address).hostAddress
        } else {
            address.address.hostAddress
        }
        return "$host/${address.networkPrefixLength}"
    }

    private fun interfaceType(caps: NetworkCapabilities): Int =
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
            else -> Libbox.InterfaceTypeOther
        }

    private fun dumpFlags(javaIface: JavaNetworkInterface, caps: NetworkCapabilities): Int {
        var flags = 0
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
        }
        if (javaIface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
        if (javaIface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
        if (javaIface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
        return flags
    }

    private fun <T> jniCall(site: String, block: () -> T): T =
        try {
            PlatformJni.call(site, block)
        } catch (error: Exception) {
            CrashTrail.mark("platform $site: ${error.message}")
            LerNetLog.e(TAG, "$site: ${error.message}", error)
            throw error
        }

    private fun jniRun(site: String, block: () -> Unit) {
        PlatformJni.run(site, { name, error ->
            CrashTrail.mark("platform $name: ${error.message}")
            LerNetLog.e(TAG, "$name: ${error.message}", error)
        }, block)
    }

    private fun <T> jniOrElse(fallback: T, block: () -> T): T =
        PlatformJni.orElse(fallback, { error ->
            CrashTrail.mark("platform getInterfaces: ${error.message}")
            LerNetLog.e(TAG, "getInterfaces: ${error.message}", error)
        }, block)

    companion object {
        private const val TAG = "LerNet.Platform"
    }
}

interface TunOwner {
    fun beforeOpenTun() = Unit

    fun retainTun(pfd: ParcelFileDescriptor)
}
