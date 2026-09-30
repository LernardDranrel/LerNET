package app.lernet.desktop

import app.lernet.engine.RunMode
import app.lernet.engine.compile.ConfigAssembler
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.win32.StdCallLibrary
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URI
import java.net.URL

internal data class TunnelHealthResult(
    val latencyMs: Long? = null,
    val error: String = "",
    val routeConflict: Boolean = false,
)

internal object WindowsRouteInspector {
    private interface IpHelper : StdCallLibrary {
        fun GetBestInterfaceEx(destination: Pointer, interfaceIndex: IntByReference): Int
        fun ConvertInterfaceIndexToLuid(interfaceIndex: Int, luid: LongByReference): Int
        fun ConvertInterfaceLuidToAlias(luid: LongByReference, alias: Pointer, length: SIZE_T): Int
    }

    private val api: IpHelper by lazy { Native.load("iphlpapi", IpHelper::class.java) }

    fun check(url: String): String? = runCatching {
        val host = URI(url).host ?: error("У адреса проверки нет имени сервера")
        val destinations = InetAddress.getAllByName(host)
        check(destinations.isNotEmpty()) { "Не удалось определить адрес сервера проверки" }
        inspectRoutes(destinations.toList(), ::bestInterface, ::interfaceAlias)
    }.getOrElse { "Не удалось подтвердить маршрут Windows через LerNET: ${it.message ?: it.javaClass.simpleName}" }

    internal fun inspectRoutes(
        destinations: List<InetAddress>,
        indexFor: (InetAddress) -> Int,
        aliasFor: (Int) -> String,
    ): String? = destinations.firstNotNullOfOrNull { address ->
        val alias = aliasFor(indexFor(address))
        check(alias.isNotBlank()) { "Не удалось определить имя сетевого интерфейса" }
        if (isLerNetInterface(alias)) null
        else "Маршрут Windows к ${address.hostAddress} идёт через «$alias», не через LerNET"
    }

    // Java exposes the driver description (e.g. "sing-tun Tunnel"), not the
    // Windows alias requested in our TUN configuration. Other clients can use
    // the same driver, so its description cannot identify our interface.
    internal fun isLerNetInterface(alias: String?): Boolean =
        alias.equals(ConfigAssembler.WINDOWS_TUN_INTERFACE, ignoreCase = true)

    internal fun interfaceAlias(index: Int): String {
        val luid = LongByReference()
        val luidResult = api.ConvertInterfaceIndexToLuid(index, luid)
        check(luidResult == 0) { "ConvertInterfaceIndexToLuid($index): $luidResult" }
        // NDIS_IF_MAX_STRING_SIZE is 256; Windows WCHAR is UTF-16.
        val characters = 257L
        val buffer = Memory(characters * 2).apply { clear() }
        val aliasResult = api.ConvertInterfaceLuidToAlias(luid, buffer, SIZE_T(characters))
        check(aliasResult == 0) { "ConvertInterfaceLuidToAlias($index): $aliasResult" }
        return buffer.getWideString(0).also { check(it.isNotBlank()) { "Empty interface alias: $index" } }
    }

    private fun bestInterface(address: InetAddress): Int {
        val family = when (address) {
            is Inet4Address -> 2 // AF_INET
            is Inet6Address -> 23 // AF_INET6 on Windows
            else -> error("Неизвестный тип IP-адреса")
        }
        val socketAddress = Memory(if (address is Inet4Address) 16L else 28L).apply {
            clear()
            setShort(0, family.toShort())
            write(if (address is Inet4Address) 4 else 8, address.address, 0, address.address.size)
        }
        val index = IntByReference()
        val result = api.GetBestInterfaceEx(socketAddress, index)
        check(result == 0) { "GetBestInterfaceEx: $result" }
        return index.value
    }
}

/** An HTTP request from the Windows process tests the path used by applications. */
internal object WindowsTunnelHealth {
    fun check(url: String, mode: RunMode, timeoutMs: Int = 5_000,
              routeCheck: (String) -> String? = WindowsRouteInspector::check): TunnelHealthResult {
        if (mode == RunMode.FULL_VPN) {
            routeCheck(url)?.let { return TunnelHealthResult(error = it, routeConflict = true) }
        }
        val proxy = if (mode == RunMode.PROXY)
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 2080)) else Proxy.NO_PROXY
        val start = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            val request = (URL(url).openConnection(proxy) as HttpURLConnection).also { connection = it }
            request.connectTimeout = timeoutMs
            request.readTimeout = timeoutMs
            request.instanceFollowRedirects = false
            request.setRequestProperty("User-Agent", "LerNET-health/1")
            val code = request.responseCode
            if (code in 200..399) TunnelHealthResult((System.nanoTime() - start) / 1_000_000)
            else TunnelHealthResult(error = "HTTP $code")
        } catch (error: Exception) {
            TunnelHealthResult(error = error.message?.take(100) ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    fun networkSignature(): String = NetworkInterface.getNetworkInterfaces().toList()
        .filter { iface ->
            runCatching { iface.isUp && !iface.isLoopback && !iface.isVirtual &&
                !WindowsRouteInspector.isLerNetInterface(
                    runCatching { WindowsRouteInspector.interfaceAlias(iface.index) }.getOrNull()
                ) }.getOrDefault(false)
        }
        .map { iface ->
            iface.name + ":" + iface.inetAddresses.toList()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { it.hostAddress }.sorted().joinToString(",")
        }
        .sorted().joinToString("|")
}
